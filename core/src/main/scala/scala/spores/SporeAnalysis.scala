package scala.spores

import scala.reflect.macros.whitebox

protected class SporeAnalysis[C <: whitebox.Context with Singleton](val ctx: C) {

  import ctx.universe._
  import ctx.universe.Flag._

  /** Strip the header and the body of a spore iff they are valid. */
  def stripSporeStructure(tree: Tree): (List[Symbol], Tree) = {
    def isCorrectHeader(valDef: ValDef) = !valDef.mods.hasFlag(MUTABLE)

    tree match {
      case Block(stmts, expr) =>
        (stmts flatMap {
          case vd: ValDef if isCorrectHeader(vd) => List(vd.symbol)
          case stmt => ctx.abort(stmt.pos, Feedback.IncorrectSporeHeader)
        }) -> expr
      case expr => (List.empty, expr)
    }
  }

  private val SporesDefinition = typeOf[spores.`package`.type]
  private val delayedSym = SporesDefinition.member(TermName("delayed"))
  private val captureSym = SporesDefinition.member(TermName("capture"))

  /** Identify spores (nullary and non-nullary) and return their params and body. */
  def readSporeFunDef(tree: Tree): (Option[Function], List[Tree], Tree) = {
    tree match {
      case f @ Function(params, body) =>
        (Some(f), params, body) // Non-nullary
      case Apply(f, List(arg)) if f.symbol == delayedSym =>
        (None, List(), arg) // Nullary spore
      case _ => ctx.abort(tree.pos, Feedback.IncorrectSporeBody)
    }
  }

  /** Check predicate is satisfied for a concrete path. */
  def isPathWith(t: Tree)(pred: TermSymbol => Boolean): Boolean = t match {
    case sel @ Select(s, _) => isPathWith(s)(pred) && pred(sel.symbol.asTerm)
    case id: Ident => pred(id.symbol.asTerm)
    case th: This => true
    /* Super is not present in paths because of SI-1938 */
    // case supr: Super => true
    case _ => false
  }

  private class SymbolCollector extends Traverser {
    var capturedSymbols = List.empty[Symbol]
    override def traverse(tree: Tree): Unit = {
      tree match {
        case app @ Apply(fun, List(captured)) if fun.symbol == captureSym =>
          debug(s"Found capture: $app")
          if (!isPathWith(captured)(_.isStable))
            ctx.abort(captured.pos, Feedback.InvalidOuterReference)
          else if (!isPathWith(captured)(!_.isLazy))
            ctx.abort(captured.pos, Feedback.InvalidLazyMember)
          else capturedSymbols ::= captured.symbol

        case _ => super.traverse(tree)
      }
    }
  }

  def collectCaptured(sporeBody: Tree): List[Symbol] = {
    debug("Collecting captured symbols...")
    val collector = new SymbolCollector
    collector.traverse(sporeBody)
    collector.capturedSymbols
  }

  private class VariableDeclarationCollector extends Traverser {
    var declaredSymbols = List.empty[Symbol]
    override def traverse(tree: Tree): Unit = {
      tree match {
        case vd: ValDef =>
          super.traverse(tree)
          declaredSymbols ::= vd.symbol
        case _ => super.traverse(tree)
      }
    }
  }

  def collectDeclared(sporeBody: Tree): List[Symbol] = {
    debug("Collecting declared symbols...")
    val collector = new VariableDeclarationCollector
    collector.traverse(sporeBody)
    collector.declaredSymbols
  }
}

protected class SporeChecker[C <: whitebox.Context with Singleton](val ctx: C)(
    val env: List[C#Symbol],
    val funSymbol: Option[C#Symbol],
    val capturedSymbols: List[C#Symbol],
    var declaredSymbols: List[C#Symbol]) {
  import ctx.universe._

  /** Check whether the owner chain of `sym` contains `owner`.
    *
    * @param sym   the symbol to be checked
    * @param owner the owner symbol that we try to find
    * @return      whether `owner` is a direct or indirect owner of `sym`
    */
  def isOwner(sym: Symbol, owner: Symbol): Boolean = {
    sym != null && (sym.owner == owner || {
      sym.owner != NoSymbol && isOwner(sym.owner, owner)
    })
  }

  /** Check whether `member` is selected from a static selector, or
    * whether its selector is transitively selected from a static symbol.
    */
  def isStaticSelector(member: Tree): Boolean = member match {
    case Select(selector, member0) =>
      val selStatic = selector.symbol.isStatic
      debug(s"checking whether $selector is static...$selStatic")
      selStatic || isStaticSelector(selector)
    case _ => false
  }

  def isSymbolChildOfSpore(childSym: Symbol) =
    funSymbol.exists(sym => isOwner(childSym, sym.asInstanceOf[Symbol]))

  /** Check the validity of symbols. Spores allow refs to symbols if:
    *
    *   1. A symbol `s` is declared in the spore header.
    *   2. A symbol `s` is captured using the `capture` syntax.
    *   3. A symbol `s` is declared within a function.
    *   4. A symbol `s` has already been declared inside the body.
    *   5. A symbol `s` is [[scala.reflect.api.Universe.NoSymbol]].
    *   6. A symbol `s` is static.
    *   7. A symbol `s` is defined within [[scala.Predef]].
    *
    * @param s Symbol of a given tree inside a spore.
    * @return Whether the symbol is valid or not.
    */
  def isSymbolValid(s: Symbol): Boolean = {
    env.contains(s) ||
    capturedSymbols.contains(s) ||
    isSymbolChildOfSpore(s) ||
    declaredSymbols.contains(s) ||
    s == NoSymbol ||
    s.isStatic ||
    s.owner == definitions.PredefModule
  }

  def isPathValid(tree: Tree): (Boolean, Option[Tree]) = {
    debug(s"checking isPathValid for $tree [${tree.symbol}]...")
    debug(s"tree class: ${tree.getClass.getName}")
    if (tree.symbol != null && isSymbolValid(tree.symbol)) (true, None)
    else
      tree match {
        case Select(pre, sel) =>
          debug(s"case 1: Select($pre, $sel)")
          isPathValid(pre)
        case Apply(Select(pre, _), _) =>
          debug(s"case 2: Apply(Select, _)")
          isPathValid(pre)
        case TypeApply(Select(pre, _), _) =>
          debug("case 3: TypeApply(Select, _)")
          isPathValid(pre)
        case TypeApply(fun, _) =>
          debug("case 4: TypeApply")
          isPathValid(fun)
        case Literal(Constant(_)) | New(_) => (true, None)
        case id: Ident => (isSymbolValid(id.symbol), None)
        case _ =>
          debug("case 7: _")
          (false, Some(tree))
      }
  }
  private class ReferenceInspector extends Traverser {
    def checkStaticSelectOnObject(applySelector: Tree, outerSelect: Select) = {
      applySelector match {
        case Select(obj, _) =>
          if (isSymbolChildOfSpore(obj.symbol))
            debug(s"OK, selected on local object $obj")
          else {
            // the invocation is OK if `obj` is transitively selected from a top-level object
            val objIsStatic = obj.symbol.isStatic || isStaticSelector(obj)
            debug(s"Is $obj transitively selected from a top-level object?")
            debug(s"$obj.symbol.isStatic: $objIsStatic")
            if (!objIsStatic)
              ctx.error(outerSelect.pos,
                        s"the invocation of '$applySelector' is not static")
          }

        case _ =>
          ctx.error(outerSelect.pos,
                    s"the invocation of '$applySelector' is not static")
      }
    }
    override def traverse(tree: Tree) {
      tree match {
        case id: Ident =>
          debug(s"Checking ident: $id")
          if (!isSymbolValid(id.symbol))
            ctx.error(id.pos, "invalid reference to " + id.symbol)

        case th: This =>
          ctx.error(th.pos, "invalid reference to " + th.symbol)

        case sp: Super =>
          ctx.error(sp.pos, "invalid reference to " + sp.symbol)

        // x.m().s
        case sel @ Select(app @ Apply(fun0, args0), _) =>
          debug(s"Checking select ($app): $sel")
          if (app.symbol.isStatic) {
            debug(s"OK, invocation of '$app' is static.")
          } else checkStaticSelectOnObject(fun0, sel)

        case sel @ Select(pre, _) =>
          debug(s"Checking select $sel")

          isPathValid(sel) match {
            case (false, Some(subtree)) => traverse(subtree)
            case (true, None) => // do nothing
            case (true, Some(subtree)) => // do nothing
            case (false, None) =>
              ctx.error(tree.pos, s"invalid reference to ${sel.symbol}")
          }

        case _ =>
          super.traverse(tree)
      }
    }
  }

  def checkReferencesInBody(sporeBody: Tree) = {
    val inspector = new ReferenceInspector
    inspector.traverse(sporeBody)
  }
}