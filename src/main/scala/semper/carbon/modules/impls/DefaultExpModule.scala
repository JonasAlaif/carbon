package semper.carbon.modules.impls

import semper.carbon.modules.ExpModule
import semper.sil.{ast => sil}
import semper.carbon.boogie._
import semper.carbon.verifier.Verifier
import semper.sil.verifier.{reasons, PartialVerificationError}
import semper.carbon.boogie.Implicits._
import semper.carbon.modules.components.DefinednessComponent
import semper.sil.ast.QuantifiedExp
import semper.sil.ast.utility.Expressions

/**
 * The default implementation of [[semper.carbon.modules.ExpModule]].
 *
 * @author Stefan Heule
 */
class DefaultExpModule(val verifier: Verifier) extends ExpModule with DefinednessComponent {

  import verifier._
  import heapModule._
  import mainModule._
  import domainModule._
  import seqModule._
  import setModule._
  import permModule._
  import inhaleModule._
  import funcPredModule._
  import exhaleModule._

  override def initialize() {
    register(this)
  }

  def name = "Expression module"
  override def translateExp(e: sil.Exp): Exp = {
    e match {
      case sil.IntLit(i) =>
        IntLit(i)
      case sil.BoolLit(b) =>
        BoolLit(b)
      case sil.NullLit() =>
        translateNull
      case l@sil.LocalVar(name) =>
        translateLocalVar(l)
      case r@sil.Result() =>
        translateResult(r)
      case f@sil.FieldAccess(rcv, field) =>
        translateLocationAccess(f)
      case sil.InhaleExhaleExp(a, b) =>
        sys.error("should not occur here (either, we inhale or exhale this expression, in which case whenInhaling/whenExhaling should be used, or the expression is not allowed to occur.")
      case p@sil.PredicateAccess(rcv, predicate) =>
        translateLocationAccess(p)
      case sil.Unfolding(acc, exp) =>
        translateExp(exp)
      case sil.Old(exp) =>
        stateModule.useOldState()
        val res = translateExp(exp)
        stateModule.useRegularState()
        res
      case sil.CondExp(cond, thn, els) =>
        CondExp(translateExp(cond), translateExp(thn), translateExp(els))
      case sil.Exists(vars, exp) =>
        vars map (v => env.define(v.localVar))
        val res = Exists(vars map translateLocalVarDecl, translateExp(exp))
        vars map (v => env.undefine(v.localVar))
        res
      case sil.Forall(vars, triggers, exp) =>
        vars map (v => env.define(v.localVar))
        val ts = triggers map (t => Trigger(t.exps map translateExp))
        val res = Forall(vars map translateLocalVarDecl, ts, translateExp(exp))
        vars map (v => env.undefine(v.localVar))
        res
      case sil.WildcardPerm() =>
        translatePerm(e)
      case sil.FullPerm() =>
        translatePerm(e)
      case sil.NoPerm() =>
        translatePerm(e)
      case sil.EpsilonPerm() =>
        translatePerm(e)
      case sil.CurrentPerm(loc) =>
        translatePerm(e)
      case sil.FractionalPerm(left, right) =>
        translatePerm(e)
      case sil.AccessPredicate(loc, perm) =>
        sys.error("not handled by expression module")
      case sil.EqCmp(left, right) =>
        left.typ match {
          case _: sil.SeqType =>
            translateSeqExp(e)
          case _: sil.SetType =>
            translateSetExp(e)
          case _: sil.MultisetType =>
            translateSetExp(e)
          case x if x == sil.Perm =>
            translatePermComparison(e)
          case _ =>
            BinExp(translateExp(left), EqCmp, translateExp(right))
        }
      case sil.NeCmp(left, right) =>
        left.typ match {
          case _: sil.SeqType =>
            translateSeqExp(e)
          case _: sil.SetType =>
            translateSetExp(e)
          case _: sil.MultisetType =>
            translateSetExp(e)
          case x if x == sil.Perm =>
            translatePermComparison(e)
          case _ =>
            BinExp(translateExp(left), NeCmp, translateExp(right))
        }
      case sil.DomainBinExp(_, sil.PermGeOp, _) |
           sil.DomainBinExp(_, sil.PermGtOp, _) |
           sil.DomainBinExp(_, sil.PermLeOp, _) |
           sil.DomainBinExp(_, sil.PermLtOp, _) =>
        translatePermComparison(e)
      case sil.DomainBinExp(_, sil.PermAddOp, _) |
           sil.DomainBinExp(_, sil.PermMulOp, _) |
           sil.DomainBinExp(_, sil.PermSubOp, _) |
           sil.DomainBinExp(_, sil.IntPermMulOp, _) |
           sil.DomainBinExp(_, sil.FracOp, _) =>
        translatePerm(e)
      case sil.DomainBinExp(left, op, right) =>
        val bop = op match {
          case sil.OrOp => Or
          case sil.LeOp => LeCmp
          case sil.LtOp => LtCmp
          case sil.GeOp => GeCmp
          case sil.GtOp => GtCmp
          case sil.AddOp => Add
          case sil.SubOp => Sub
          case sil.DivOp => IntDiv
          case sil.ModOp => Mod
          case sil.MulOp => Mul
          case sil.AndOp => And
          case sil.ImpliesOp => Implies
          case _ =>
            sys.error("should be handeled further above")
        }
        BinExp(translateExp(left), bop, translateExp(right))
      case sil.Neg(exp) =>
        UnExp(Neg, translateExp(exp))
      case sil.Not(exp) =>
        UnExp(Not, translateExp(exp))
      case fa@sil.FuncApp(func, args) =>
        translateFuncApp(fa)
      case fa@sil.DomainFuncApp(func, args, _) =>
        translateDomainFuncApp(fa)

      case seqExp@sil.EmptySeq(elemTyp) =>
        translateSeqExp(seqExp)
      case seqExp@sil.ExplicitSeq(elems) =>
        translateSeqExp(seqExp)
      case seqExp@sil.RangeSeq(low, high) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqAppend(left, right) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqIndex(seq, idx) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqTake(seq, n) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqDrop(seq, n) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqContains(elem, seq) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqUpdate(seq, idx, elem) =>
        translateSeqExp(seqExp)
      case seqExp@sil.SeqLength(seq) =>
        translateSeqExp(seqExp)

      case setExp@sil.EmptySet(elemTyp) => translateSetExp(setExp)
      case setExp@sil.ExplicitSet(elems) => translateSetExp(setExp)
      case setExp@sil.EmptyMultiset(elemTyp) => translateSetExp(setExp)
      case setExp@sil.ExplicitMultiset(elems) => translateSetExp(setExp)
      case setExp@sil.AnySetUnion(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetIntersection(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetSubset(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetMinus(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetContains(left, right) => translateSetExp(setExp)
      case setExp@sil.AnySetCardinality(_) => translateSetExp(setExp)
    }
  }

  override def translateLocalVar(l: sil.LocalVar): LocalVar = {
    env.get(l)
  }

  override def simplePartialCheckDefinedness(e: sil.Exp, error: PartialVerificationError): Stmt = {
    e match {
      case sil.Div(a, b) =>
        Assert(translateExp(b) !== IntLit(0), error.dueTo(reasons.DivisionByZero(b)))
      case sil.Mod(a, b) =>
        Assert(translateExp(b) !== IntLit(0), error.dueTo(reasons.DivisionByZero(b)))
      case sil.FractionalPerm(a, b) =>
        Assert(translateExp(b) !== IntLit(0), error.dueTo(reasons.DivisionByZero(b)))
      case _ => Nil
    }
  }

  override def checkDefinedness(e: sil.Exp, error: PartialVerificationError): Stmt = {
    MaybeCommentBlock(s"Check definedness of $e",
      MaybeStmt(checkDefinednessImpl(e, error, topLevel = true),
        stateModule.assumeGoodState))
  }

  private def checkDefinednessImpl(e: sil.Exp, error: PartialVerificationError, topLevel: Boolean): Stmt = {
    e match {
      case sil.And(e1, e2) if true /*topLevel*/ =>
        checkDefinednessImpl(e1, error, topLevel = true) ::
          If(translateExp(Expressions.purify(e1)), checkDefinednessImpl(e2, error, topLevel = true), Statements.EmptyStmt) ::
          Nil
      case sil.Implies(e1, e2) if true /*topLevel*/ =>
        checkDefinednessImpl(e1, error, topLevel = true) :: 
          If(translateExp(e1), checkDefinednessImpl(e2, error, topLevel = true), Statements.EmptyStmt) ::
          Nil
      case sil.CondExp(c, e1, e2) if true /*topLevel*/ =>
        checkDefinednessImpl(c, error, topLevel = true) :: 
          If(translateExp(c), checkDefinednessImpl(e1, error, topLevel = true), checkDefinednessImpl(e2, error, topLevel = true)) ::
          Nil
      case sil.Or(e1, e2) if true /*topLevel*/ =>
        checkDefinednessImpl(e1, error, topLevel = true) :: // short-circuiting evaluation:
          If(UnExp(Not, translateExp(e1)), checkDefinednessImpl(e2, error, topLevel = true), Statements.EmptyStmt) ::
          Nil
      case _ =>
        def translate: Seqn = {
          val checks = components map (_.partialCheckDefinedness(e, error))
          val stmt = checks map (_._1())
          val stmt2 = for (sub <- e.subnodes if sub.isInstanceOf[sil.Exp]) yield {
            checkDefinednessImpl(sub.asInstanceOf[sil.Exp], error, topLevel = e.isInstanceOf[sil.Unfolding] && (e.asInstanceOf[sil.Unfolding].exp eq sub))
          }
          val stmt3 = checks map (_._2())
          stmt ++ stmt2 ++ stmt3 ++
            MaybeCommentBlock("Free assumptions", allFreeAssumptions(e))
        }
        
        if (e.isInstanceOf[sil.QuantifiedExp]) {
          val bound_vars = e.asInstanceOf[sil.QuantifiedExp].variables
	  bound_vars map (v => env.define(v.localVar))
	  val res = handleQuantifiedLocals(e,translate)	  
	  bound_vars map (v => env.undefine(v.localVar))
          res        
        } else {
          val res = if (e.isInstanceOf[sil.Old]) {
            stateModule.useOldState()
            val res = translate
            stateModule.useRegularState()
            res
          } else {
            translate
          }
          handleQuantifiedLocals(e, res)
        }
    }
  }


  def handleQuantifiedLocals(e: sil.Exp, res: Stmt): Stmt = {
    // introduce local variables for the variables in quantifications. we do this by first check
    // definedness without worrying about missing variable declarations, and then replace all of them
    // with fresh variables.
    e match {
      case QuantifiedExp(vars, exp) =>
        Transformer.transform(res, {
          case v@LocalVar(name, _) =>
            val namespace = verifier.freshNamespace("exp.quantifier")
            val newVars = vars map (x => (translateLocalVar(x.localVar),
              // we use a fresh namespace to make sure we get fresh variables
              Identifier(x.name)(namespace)
              ))
            newVars.find(x => (name == x._1.name)) match {
              case None => v // no change
              case Some((x, xb)) =>
                // use the new variable
                LocalVar(xb, x.typ)
            }
        })()
      case _ => res
    }
  }
  // (AS) TODO: this is really "checkDefinednessOfSpecAndInhale" - should be renamed 
  override def checkDefinednessOfSpec(e: sil.Exp, error: PartialVerificationError): Stmt = {
    e match {
      case sil.And(e1, e2) =>
        checkDefinednessOfSpec(e1, error) ::
          checkDefinednessOfSpec(e2, error) ::
          Nil
      case sil.Implies(e1, e2) =>
        checkDefinedness(e1, error) ++
          If(translateExp(e1), checkDefinednessOfSpec(e2, error), Statements.EmptyStmt)
      case sil.CondExp(c, e1, e2) =>
        checkDefinedness(c, error) ++
          If(translateExp(c), checkDefinednessOfSpec(e1, error), checkDefinednessOfSpec(e2, error))
      case _ =>
        checkDefinedness(e, error) ++
          inhale(e)
    }
  }

  override def checkDefinednessOfSpecAndExhale(e: sil.Exp, definednessError: PartialVerificationError, exhaleError: PartialVerificationError): Stmt = {
    e match {
      case sil.And(e1, e2) =>
        checkDefinednessOfSpecAndExhale(e1, definednessError, exhaleError) ::
          checkDefinednessOfSpecAndExhale(e2, definednessError, exhaleError) ::
          Nil
      case sil.Implies(e1, e2) =>
        checkDefinedness(e1, definednessError) ++
          If(translateExp(e1), checkDefinednessOfSpecAndExhale(e2, definednessError, exhaleError), Statements.EmptyStmt)
      case sil.CondExp(c, e1, e2) =>
        checkDefinedness(c, definednessError) ++
          If(translateExp(c),
            checkDefinednessOfSpecAndExhale(e1, definednessError, exhaleError),
            checkDefinednessOfSpecAndExhale(e2, definednessError, exhaleError))
      case _ =>
        checkDefinedness(e, definednessError) ++
          exhale(Seq((e, exhaleError)))
    }
  }

  override def allFreeAssumptions(e: sil.Exp): Stmt = {
    def translate: Seqn = {
      val stmt = components map (_.freeAssumptions(e))
      val stmt2 = for (sub <- e.subnodes if sub.isInstanceOf[sil.Exp]) yield {
        allFreeAssumptions(sub.asInstanceOf[sil.Exp])
      }
      stmt ++ stmt2
    }
    if (e.isInstanceOf[sil.Old]) {
      stateModule.useOldState()
      val res = translate
      stateModule.useRegularState()
      res
    } else {
      translate
    }
  }
}
