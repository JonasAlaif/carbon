/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.carbon.modules.impls

import viper.carbon.modules._
import viper.silver.ast.{PredicateAccess, PredicateAccessPredicate, Unfolding}
import viper.silver.{ast => sil}
import viper.carbon.boogie._
import viper.carbon.verifier.{Environment, Verifier}
import viper.carbon.boogie.Implicits._
import viper.silver.ast.utility._
import viper.carbon.modules.components.{InhaleComponent, ExhaleComponent, DefinednessComponent}
import viper.silver.verifier.{NullPartialVerificationError, errors, PartialVerificationError}

/**
 * The default implementation of a [[viper.carbon.modules.FuncPredModule]].
 */
class DefaultFuncPredModule(val verifier: Verifier) extends FuncPredModule
with DefinednessComponent with ExhaleComponent with InhaleComponent {
  def name = "Function and predicate module"

  import verifier._
  import typeModule._
  import mainModule._
  import stateModule._
  import expModule._
  import exhaleModule._
  import inhaleModule._
  import heapModule._
  import permModule._

  implicit val fpNamespace = verifier.freshNamespace("funcpred")

  lazy val heights = Functions.heights(verifier.program)
  private val assumeFunctionsAboveName = Identifier("AssumeFunctionsAbove")
  private val assumeFunctionsAbove: Const = Const(assumeFunctionsAboveName)
  private val specialRefName = Identifier("special_ref")
  private val specialRef = Const(specialRefName)
  private val limitedPostfix = "'"
  private val triggerFuncPostfix = "#trigger"
  private val framePostfix = "#frame"
  private val frameTypeName = "FrameType"
  private val frameType = NamedType(frameTypeName)
  private val emptyFrameName = Identifier("EmptyFrame")
  private val emptyFrame = Const(emptyFrameName)
  private val combineFramesName = Identifier("CombineFrames")
  private val frameFragmentName = Identifier("FrameFragment")
  private val resultName = Identifier("Result")
  private val insidePredicateName = Identifier("InsidePredicate")

  override def preamble = {
    val fp = if (verifier.program.functions.isEmpty) Nil
    else {
      val m = heights.values.max
      DeclComment("Function heights (higher height means its body is available earlier):") ++
        (for (i <- m to 0 by -1) yield {
          val fs = heights.toSeq filter (p => p._2 == i) map (_._1.name)
          DeclComment(s"- height $i: ${fs.mkString(", ")}")
        }) ++
        ConstDecl(assumeFunctionsAboveName, Int)
    }
    fp ++
      CommentedDecl("Declarations for function framing",
        TypeDecl(frameType) ++
          ConstDecl(emptyFrameName, frameType) ++
          Func(frameFragmentName, Seq(LocalVarDecl(Identifier("t"), TypeVar("T"))), frameType) ++
          Func(combineFramesName,
            Seq(LocalVarDecl(Identifier("a"), frameType), LocalVarDecl(Identifier("b"), frameType)),
            frameType), size = 1) ++
      CommentedDecl("Function for recording enclosure of one predicate instance in another",
        Func(insidePredicateName,
          Seq(
            LocalVarDecl(Identifier("x"), refType),
            LocalVarDecl(Identifier("p"), predicateVersionFieldType("A")),
            LocalVarDecl(Identifier("v"), Int),
            LocalVarDecl(Identifier("y"), refType),
            LocalVarDecl(Identifier("q"), predicateVersionFieldType("B")),
            LocalVarDecl(Identifier("w"), Int)
          ),
          Bool), size = 1) ++
      ConstDecl(specialRefName, refType, unique = true) ++
      CommentedDecl(s"Transitivity of ${insidePredicateName.name}", {
        val vars1 = Seq(
          LocalVarDecl(Identifier("x"), refType),
          LocalVarDecl(Identifier("p"), predicateVersionFieldType("A")),
          LocalVarDecl(Identifier("v"), Int)
        )
        val vars2 = Seq(
          LocalVarDecl(Identifier("y"), refType),
          LocalVarDecl(Identifier("q"), predicateVersionFieldType("B")),
          LocalVarDecl(Identifier("w"), Int)
        )
        val vars3 = Seq(
          LocalVarDecl(Identifier("z"), refType),
          LocalVarDecl(Identifier("r"), predicateVersionFieldType("C")),
          LocalVarDecl(Identifier("u"), Int)
        )
        val f1 = FuncApp(insidePredicateName, (vars1 ++ vars2) map (_.l), Bool)
        val f2 = FuncApp(insidePredicateName, (vars2 ++ vars3) map (_.l), Bool)
        val f3 = FuncApp(insidePredicateName, (vars1 ++ vars3) map (_.l), Bool)
        Axiom(
          Forall(
            vars1 ++ vars2 ++ vars3,
            Trigger(Seq(f1, f2)),
            (f1 && f2) ==> f3
          )
        )
      }, size = 1) ++
      CommentedDecl(s"Knowledge that two identical instances of the same predicate cannot be inside each other", {
        val p = LocalVarDecl(Identifier("p"), predicateVersionFieldType())
        val vars = Seq(
          LocalVarDecl(Identifier("x"), refType),
          p,
          LocalVarDecl(Identifier("v"), Int),
          LocalVarDecl(Identifier("y"), refType),
          p,
          LocalVarDecl(Identifier("w"), Int)
        )
        val f = FuncApp(insidePredicateName, vars map (_.l), Bool)
        Axiom(
          Forall(
            vars.distinct,
            Trigger(f),
            f ==> (vars(0).l !== vars(3).l)
          )
        )
      }, size = 1)
  }

  override def initialize() {
    expModule.register(this)
    inhaleModule.register(this)
    exhaleModule.register(this)
  }

    override def translateFunction(f: sil.Function): Seq[Decl] = {
    env = Environment(verifier, f)
    val res = MaybeCommentedDecl(s"Translation of function ${f.name}",
      MaybeCommentedDecl("Uninterpreted function definitions", functionDefinitions(f), size = 1) ++
        (if (f.isAbstract) Nil else
        MaybeCommentedDecl("Definitional axiom", definitionalAxiom(f), size = 1)) ++
        MaybeCommentedDecl("Framing axioms", framingAxiom(f), size = 1) ++
        MaybeCommentedDecl("Postcondition axioms", postconditionAxiom(f), size = 1) ++
        MaybeCommentedDecl("State-independent trigger function", triggerFunction(f), size = 1) ++
        MaybeCommentedDecl("Check contract well-formedness and postcondition", checkFunctionDefinedness(f), size = 1)
      , nLines = 2)
    env = null
    res
  }

  private def functionDefinitions(f: sil.Function): Seq[Decl] = {
    val typ = translateType(f.typ)
    val args = heapModule.stateContributions ++ (f.formalArgs map translateLocalVarDecl)
    val name = Identifier(f.name)
    val func = Func(name, args, typ)
    val name2 = Identifier(f.name + limitedPostfix)
    val func2 = Func(name2, args, typ)
    val funcApp = FuncApp(name, args map (_.l), Bool)
    val funcApp2 = FuncApp(name2, args map (_.l), Bool)
    func ++ func2 ++
      Axiom(Forall(args, Trigger(funcApp), funcApp === funcApp2))
  }

  override def translateFuncApp(fa: sil.FuncApp) = {
    translateFuncApp(fa.funcname, heapModule.currentState ++ (fa.args map translateExp), fa.typ)
  }
  def translateFuncApp(fname : String, args: Seq[Exp], typ: sil.Type) = {
    FuncApp(Identifier(fname), args, translateType(typ))
  }

  private def assumeFunctionsAbove(i: Int): Exp =
    assumeFunctionsAbove > IntLit(i)

  def assumeAllFunctionDefinitions: Stmt = {
    if (verifier.program.functions.isEmpty) Nil
    else Assume(assumeFunctionsAbove(((heights map (_._2)).max) + 1))
  }

  private def definitionalAxiom(f: sil.Function): Seq[Decl] = {
    val height = heights(f)
    val heap = heapModule.stateContributions
    val args = f.formalArgs map translateLocalVarDecl
    val fapp = translateFuncApp(f.name, (heap ++ args) map (_.l), f.typ)
    val body = transformLimited(translateExp(f.body.get),height)
    val outerUnfoldings : Seq[Unfolding] = Functions.recursiveCallsAndSurroundingUnfoldings(f).map((pair) => pair._2.headOption).flatten
    val predicateTriggers = outerUnfoldings.map{case Unfolding(PredicateAccessPredicate(predacc : PredicateAccess,perm),exp) => predicateTrigger(predacc)}
    Axiom(Forall(
      stateModule.stateContributions ++ args,
      Trigger((if (predicateTriggers.isEmpty) Seq(staticGoodState,fapp) else Seq(staticGoodState, transformLimited(fapp)) ++ predicateTriggers)),
      (staticGoodState && assumeFunctionsAbove(height)) ==>
        (fapp === body)
    ))
  }

  /**
   * Transform all function applications to their limited form.
   * If height is provided (i.e., non-negative), functions of above that height need not have their applications replaced with the limited form.
   */
  private def transformLimited(exp: Exp, heightToSkip : Int = -1): Exp = {
    def transformer: PartialFunction[Exp, Option[Exp]] = {
      case FuncApp(recf, recargs, t) if recf.namespace == fpNamespace && (heightToSkip == -1 || heights(verifier.program.findFunction(recf.name)) <= heightToSkip) => {
        // change all function applications to use the limited form, and still go through all arguments
        Some(FuncApp(Identifier(recf.name + limitedPostfix), recargs map (_.transform(transformer)), t))
      }
    }
    exp transform transformer
  }

  private def postconditionAxiom(f: sil.Function): Seq[Decl] = {
    val height = heights(f)
    val heap = heapModule.stateContributions
    val args = f.formalArgs map translateLocalVarDecl
    val fapp = translateFuncApp(f.name, (heap ++ args) map (_.l), f.typ)
    val res = translateResult(sil.Result()(f.typ))
    for (post <- f.posts) yield {
      val bPost = translateExp(post) transform {
        case e if e == res => Some(fapp)
      }
      Axiom(Forall(
        stateModule.stateContributions ++ args,
        Trigger(Seq(staticGoodState, transformLimited(fapp))),
        (staticGoodState && assumeFunctionsAbove(height)) ==> transformLimited(bPost)))
    }
  }

  private def triggerFunction(f: sil.Function): Seq[Decl] = {
    Func(Identifier(f.name + triggerFuncPostfix), f.formalArgs map translateLocalVarDecl, Bool)
  }

  private def triggerFuncApp(f: sil.FuncApp): Exp = {
    FuncApp(Identifier(f.funcname + triggerFuncPostfix), f.args map translateExp, Bool)
  }

  private def framingAxiom(f: sil.Function): Seq[Decl] = {
    val typ = translateType(f.typ)
    val args = f.formalArgs map translateLocalVarDecl
    val name = Identifier(f.name + framePostfix)
    val func = Func(name, LocalVarDecl(Identifier("frame"), frameType) ++ args, typ)
    val funcApp = FuncApp(name, functionFrame(f.pres) ++ (args map (_.l)), Bool)
    val heap = heapModule.stateContributions
    val funcApp2 = translateFuncApp(f.name, (heap ++ args) map (_.l), f.typ)
    func ++
      Axiom(Forall(
        stateModule.stateContributions ++ args,
        Trigger(Seq(staticGoodState, transformLimited(funcApp2))),
        staticGoodState ==> (transformLimited(funcApp2) === funcApp)))
  }

  /** Generate an expression that represents the state a function can depend on
    * (as determined by examining the functions preconditions).
    */
  private def functionFrame(pres: Seq[sil.Exp]): Exp = {
    pres match {
      case Nil => emptyFrame
      case pre +: Nil => functionFrameHelper(pre)
      case p +: ps => combineFrames(functionFrameHelper(p), functionFrame(ps))
    }
  }
  private def combineFrames(a: Exp, b: Exp) = {
    FuncApp(combineFramesName, Seq(a, b), frameType)
  }
  private def functionFrameHelper(pre: sil.Exp): Exp = {
    def frameFragment(e: Exp) = {
      FuncApp(frameFragmentName, Seq(e), frameType)
    }
    pre match {
      case sil.AccessPredicate(la, perm) =>
        frameFragment(translateLocationAccess(la))
      case sil.Implies(e0, e1) =>
        frameFragment(CondExp(translateExp(e0), functionFrameHelper(e1), emptyFrame))
      case sil.And(e0, e1) =>
        combineFrames(functionFrameHelper(e0), functionFrameHelper(e1))
      case sil.CondExp(con, thn, els) =>
        frameFragment(CondExp(translateExp(con), functionFrameHelper(thn), functionFrameHelper(els)))
      case sil.Unfolding(_, _) =>
        // the predicate of the unfolding expression needs to have been mentioned
        // already (framing check), so we can safely ignore it now
        emptyFrame
      case e =>
        emptyFrame
    }
  }

  private def checkFunctionDefinedness(f: sil.Function) = {
    val args = f.formalArgs map translateLocalVarDecl
    val res = sil.Result()(f.typ)
    val init = MaybeCommentBlock("Initializing the state",
      stateModule.initState ++ (f.formalArgs map (a => allAssumptionsAboutValue(a.typ,mainModule.translateLocalVarDecl(a),true))) ++ assumeAllFunctionDefinitions)
    val initOld = MaybeCommentBlock("Initializing the old state", stateModule.initOldState)
    val checkPre = MaybeCommentBlock("Inhaling precondition (with checking)",
      f.pres map (e => checkDefinednessOfSpecAndInhale(e, errors.FunctionNotWellformed(f))))
      val checkExp = if (f.isAbstract) MaybeCommentBlock("(no definition for abstract function)",Nil) else
        MaybeCommentBlock("Check definedness of function body",
        expModule.checkDefinedness(f.body.get, errors.FunctionNotWellformed(f)))
      val exp = if (f.isAbstract) MaybeCommentBlock("(no definition for abstract function)",Nil) else
        MaybeCommentBlock("Translate function body",
        translateResult(res) := translateExp(f.body.get))
    val checkPost = if (f.isAbstract)
      MaybeCommentBlock("Checking definedness of postcondition (no body)",
      f.posts map (e => checkDefinedness(e, errors.ContractNotWellformed(e))))
    else
      MaybeCommentBlock("Exhaling postcondition (with checking)",
      f.posts map (e => checkDefinednessOfSpecAndExhale(e, errors.ContractNotWellformed(e), errors.PostconditionViolated(e, f))))
    val body = Seq(init, initOld, checkPre, checkExp, exp, checkPost)
    Procedure(Identifier(f.name + "#definedness"), args, translateResultDecl(res), body)
  }

  private def translateResultDecl(r: sil.Result) = LocalVarDecl(resultName, translateType(r.typ))
  override def translateResult(r: sil.Result) = translateResultDecl(r).l

  override def simplePartialCheckDefinedness(e: sil.Exp, error: PartialVerificationError, makeChecks: Boolean): Stmt = {
    if(makeChecks)
      e match {
        case fa@sil.FuncApp(f, args) => {
          val funct = verifier.program.findFunction(f);
          val pres = funct.pres map (e => Expressions.instantiateVariables(e, funct.formalArgs, args))
          if (pres.isEmpty) Nil
          else
            NondetIf(
              MaybeComment("Exhale precondition of function application", exhale(pres map (e => (e, errors.PreconditionInAppFalse(fa))))) ++
                MaybeComment("Stop execution", Assume(FalseLit()))
            )
        }
        case _ => Nil
      }
    else Nil
  }

  private var tmpStateId = -1
  override def partialCheckDefinedness(e: sil.Exp, error: PartialVerificationError, makeChecks: Boolean): (() => Stmt, () => Stmt) = {
    e match {
      case u@sil.Unfolding(acc@sil.PredicateAccessPredicate(loc, perm), exp) =>
        tmpStateId += 1
        val tmpStateName = if (tmpStateId == 0) "Unfolding" else s"Unfolding$tmpStateId"
        val (stmt, state) = stateModule.freshTempState(tmpStateName)
        def before() = {
          stmt ++ unfoldPredicate(acc, error)
        }
        def after() = {
          tmpStateId -= 1
          stateModule.restoreState(state)
          Nil
        }
        (before, after)
      case _ => (() => simplePartialCheckDefinedness(e, error, makeChecks), () => Nil)
    }
  }


  override def toTriggers(e: Exp): Exp = {
    transformLimited(e)
  }

  // --------------------------------------------

  override def translatePredicate(p: sil.Predicate): Seq[Decl] = {
    env = Environment(verifier, p)
    val res = MaybeCommentedDecl(s"Translation of predicate ${p.name}",
      predicateGhostFieldDecl(p))
    env = null
    res
  }

  override def translateFold(fold: sil.Fold): Stmt = {
    fold match {
      case sil.Fold(acc@sil.PredicateAccessPredicate(pa@sil.PredicateAccess(_, _), perm)) =>
        checkDefinedness(acc, errors.FoldFailed(fold)) ++
          checkDefinedness(perm, errors.FoldFailed(fold)) ++
          foldPredicate(acc, errors.FoldFailed(fold))
    }
  }

  private var duringFold = false
  private var foldInfo: sil.PredicateAccessPredicate = null
  private def foldPredicate(acc: sil.PredicateAccessPredicate, error: PartialVerificationError): Stmt = {
    duringFold = true
    foldInfo = acc
    val stmt = Assume(predicateTrigger(acc.loc)) ++
      exhale(Seq((acc.loc.predicateBody(verifier.program).get, error)), havocHeap = false) ++
      inhale(acc)
    foldInfo = null
    duringFold = false
    stmt
  }

  private var duringUnfold = false
  private var unfoldInfo: sil.PredicateAccessPredicate = null
  override def translateUnfold(unfold: sil.Unfold): Stmt = {
    unfold match {
      case sil.Unfold(acc@sil.PredicateAccessPredicate(pa@sil.PredicateAccess(_, _), perm)) =>
        checkDefinedness(acc, errors.UnfoldFailed(unfold)) ++
          checkDefinedness(perm, errors.UnfoldFailed(unfold)) ++
          unfoldPredicate(acc, errors.UnfoldFailed(unfold))
    }
  }

  private def unfoldPredicate(acc: sil.PredicateAccessPredicate, error: PartialVerificationError): Stmt = {
    val oldDuringUnfold = duringUnfold
    val oldUnfoldInfo = unfoldInfo
    val oldDuringFold = duringFold
    duringFold = false
    duringUnfold = true
    unfoldInfo = acc
    val stmt = Assume(predicateTrigger(acc.loc)) ++
      exhale(Seq((acc, error)), havocHeap = false) ++
      inhale(acc.loc.predicateBody(verifier.program).get)
    unfoldInfo = oldUnfoldInfo
    duringUnfold = oldDuringUnfold
    duringFold = oldDuringFold
    stmt
  }

  override def exhaleExp(e: sil.Exp, error: PartialVerificationError): Stmt = {
    e match {
      case pap@sil.PredicateAccessPredicate(loc@sil.PredicateAccess(_, _), perm) if duringUnfold && currentPhaseId == 0 =>
        val oldVersion = LocalVar(Identifier("oldVersion"), Int)
        val newVersion = LocalVar(Identifier("newVersion"), Int)
        val curVersion = translateExp(loc)
        val stmt: Stmt = if (exhaleTmpStateId >= 0) Nil else (oldVersion := curVersion) ++
          Havoc(Seq(newVersion)) ++
          Assume(oldVersion < newVersion) ++
          (curVersion := newVersion)
        MaybeCommentBlock("Update version of predicate",
          If(hasDirectPerm(loc), stmt, Nil))
      case pap@sil.PredicateAccessPredicate(loc@sil.PredicateAccess(_, _), perm) if duringFold =>
        MaybeCommentBlock("Record predicate instance information",
          insidePredicate(foldInfo, pap))
      case _ => Nil
    }
  }

  private def insidePredicate(p1: sil.PredicateAccessPredicate, p2: sil.PredicateAccessPredicate): Stmt = {
    val allArgs1 = p1.loc.args.zipWithIndex
    val args1 = allArgs1 filter (x => x._1.typ == sil.Ref)
    val allArgs2 = p2.loc.args.zipWithIndex
    val args2 = allArgs2 filter (x => x._1.typ == sil.Ref)
    // go through all combinations of ref-type arguments
    for (a1 <- args1; a2 <- args2) yield {
      val (arg1, idx1) = a1
      val (arg2, idx2) = a2
      // we replace the argument we are currently considering with 'specialRef'
      val newargs1 = allArgs1 map (e => if (e._2 != idx1) translateExp(e._1) else specialRef)
      val newargs2 = allArgs2 map (e => if (e._2 != idx2) translateExp(e._1) else specialRef)
      Assume(FuncApp(insidePredicateName,
        Seq(translateExp(arg1),
          translateLocation(verifier.program.findPredicate(p1.loc.predicateName), newargs1),
          translateExp(p1.loc),
          translateExp(arg2),
          translateLocation(verifier.program.findPredicate(p2.loc.predicateName), newargs2),
          translateExp(p2.loc)),
        Bool))
    }
  }

  var exhaleTmpStateId = -1
  var extraUnfolding = false
  override def inhaleExp(e: sil.Exp): Stmt = {
    e match {
      case sil.Unfolding(acc, exp) =>
        Nil
      case pap@sil.PredicateAccessPredicate(loc@sil.PredicateAccess(_, _), perm) =>
        val res: Stmt = if (extraUnfolding) {
          exhaleTmpStateId += 1
          extraUnfolding = false
          val tmpStateName = if (exhaleTmpStateId == 0) "ExtraUnfolding" else s"ExtraUnfolding$exhaleTmpStateId"
          val (stmt, state) = stateModule.freshTempState(tmpStateName)
          val r = stmt ++ unfoldPredicate(pap, NullPartialVerificationError)
          extraUnfolding = true
          exhaleTmpStateId -= 1
          stateModule.restoreState(state)
          r
        } else Nil
        CommentBlock("Extra unfolding of predicate",
          res ++ (if (duringUnfold) insidePredicate(unfoldInfo, pap) else Nil))
      case _ => Nil
    }
  }
}
