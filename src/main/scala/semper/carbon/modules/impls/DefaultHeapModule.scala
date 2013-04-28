package semper.carbon.modules.impls

import semper.carbon.modules._
import semper.carbon.modules.components.{DefinednessComponent, StmtComponent, StateComponent}
import semper.sil.{ast => sil}
import semper.carbon.boogie._
import semper.carbon.boogie.Implicits._
import semper.carbon.verifier.Verifier
import semper.sil.verifier.{reasons, PartialVerificationError}

/**
 * The default implementation of a [[semper.carbon.modules.HeapModule]].
 *
 * @author Stefan Heule
 */
class DefaultHeapModule(val verifier: Verifier) extends HeapModule with StmtComponent with DefinednessComponent {

  import verifier._
  import typeModule._
  import expModule._
  import stateModule._
  import permModule._

  def name = "Heap module"
  implicit val heapNamespace = verifier.freshNamespace("heap")
  val fieldNamespace = verifier.freshNamespace("heap.fields")
  // a fresh namespace for every axiom
  def axiomNamespace = verifier.freshNamespace("heap.axiom")

  override def initialize() {
    stateModule.register(this)
    stmtModule.register(this)
    expModule.register(this)
  }

  private val fieldTypeName = "Field"
  private def fieldTypeOf(t: Type) = NamedType(fieldTypeName, t)
  override def fieldType = NamedType(fieldTypeName, TypeVar("T"))
  private val heapTyp = NamedType("HeapType")
  private val heapName = Identifier("Heap")
  private val exhaleHeapName = Identifier("ExhaleHeap")
  private val exhaleHeap = LocalVar(exhaleHeapName, heapTyp)
  private var heap: Exp = GlobalVar(heapName, heapTyp)
  private val nullName = Identifier("null")
  private val nullLit = Const(nullName)
  private val freshObjectName = Identifier("freshObj")
  private val freshObjectVar = LocalVar(freshObjectName, refType)
  private val allocName = Identifier("$allocated")(fieldNamespace)
  private val identicalOnKnownLocsName = Identifier("IdenticalOnKnownLocations")
  override def refType = NamedType("Ref")

  override def preamble = {
    val obj = LocalVarDecl(Identifier("o")(axiomNamespace), refType)
    val refField = LocalVarDecl(Identifier("f")(axiomNamespace), fieldTypeOf(refType))
    val obj_refField = lookup(LocalVar(heapName, heapTyp), obj.l, refField.l)
    val field = LocalVarDecl(Identifier("f")(axiomNamespace), fieldType)
    TypeDecl(refType) ++
      GlobalVarDecl(heapName, heapTyp) ++
      ConstDecl(nullName, refType) ++
      TypeDecl(fieldType) ++
      TypeAlias(heapTyp, MapType(Seq(refType, fieldType), TypeVar("T"), Seq(TypeVar("T")))) ++
      ConstDecl(allocName, NamedType(fieldTypeName, Bool), unique = true) ++
      // all heap-lookups yield allocated objects or null
      Axiom(Forall(
        obj ++
          refField ++
          stateModule.stateContributions,
        Trigger(Seq(staticGoodState, obj_refField)),
        validReference(obj_refField))) ++
      Func(identicalOnKnownLocsName,
        Seq(LocalVarDecl(heapName, heapTyp), LocalVarDecl(exhaleHeapName, heapTyp)) ++ staticMask,
        Bool) ++ {
      val h = LocalVarDecl(heapName, heapTyp)
      val eh = LocalVarDecl(exhaleHeapName, heapTyp)
      val vars = Seq(h, eh) ++ staticMask
      val identicalFuncApp = FuncApp(identicalOnKnownLocsName, vars map (_.l), Bool)
      Axiom(Forall(
        vars ++ Seq(obj, field),
        Trigger(Seq(identicalFuncApp, lookup(h.l, obj.l, field.l))) ++
          Trigger(Seq(identicalFuncApp, lookup(eh.l, obj.l, field.l))),
        identicalFuncApp ==>
          staticPermissionPositive(obj.l, field.l) ==>
          (lookup(h.l, obj.l, field.l) === lookup(eh.l, obj.l, field.l))
      )
      )
    }
  }

  override def translateField(f: sil.Field) = {
    ConstDecl(locationIdentifier(f), NamedType(fieldTypeName, translateType(f.typ)), unique = true)
  }

  /** Return the identifier corresponding to a SIL location. */
  private def locationIdentifier(f: sil.Location): Identifier = {
    Identifier(f.name)(fieldNamespace)
  }

  /** Returns a heap-lookup of the allocated field of an object. */
  private def alloc(o: Exp) = lookup(heap, o, Const(allocName))

  /** Returns a heap-lookup for o.f in a given heap h. */
  private def lookup(h: Exp, o: Exp, f: Exp) = MapSelect(h, Seq(o, f))

  override def translateFieldAccess(f: sil.FieldAccess): Exp = {
    MapSelect(heap, Seq(translateExp(f.rcv), locationMaskIndex(f)))
  }

  override def locationMaskIndex(l: sil.LocationAccess): Const = {
    Const(locationIdentifier(l.loc))
  }

  override def handleStmt(stmt: sil.Stmt): Stmt = {
    stmt match {
      case sil.FieldAssign(lhs, rhs) =>
        translateFieldAccess(lhs) := translateExp(rhs)
      case sil.NewStmt(target) =>
        Havoc(freshObjectVar) ::
          // assume the fresh object is non-null and not allocated yet.
          // this means that whenever we allocate a new object and havoc freshObjectVar, we
          // assume that we consider a newly allocated cell, which gives the prover
          // the information that this object is different from anything allocated
          // earlier.
          Assume((freshObjectVar !== nullLit) && alloc(freshObjectVar).not) ::
          (alloc(freshObjectVar) := TrueLit()) ::
          (translateExp(target) := freshObjectVar) ::
          Nil
      case sil.MethodCall(_, _, targets) =>
        targets filter (_.typ == sil.Ref) map translateExp map {
          t =>
            Assume(validReference(t))
        }
      case _ => Statements.EmptyStmt
    }
  }

  override def assumptionAboutParameter(typ: sil.Type, variable: LocalVar): Option[Exp] = {
    typ match {
      case sil.Ref => Some(validReference(variable))
      case _ => None
    }
  }

  private def validReference(exp: Exp): Exp = {
    exp === nullLit || alloc(exp)
  }

  override def translateNull: Exp = nullLit

  def initState: Stmt = {
    Nil
  }
  def initOldState: Stmt = {
    Assume(Old(heap) === heap)
  }

  def stateContributions: Seq[LocalVarDecl] = Seq(LocalVarDecl(heapName, heapTyp))
  def currentStateContributions: Seq[Exp] = Seq(heap)

  override type StateSnapshot = (Int, Exp)
  private var curTmpStateId = -1

  override def freshTempState: (Stmt, StateSnapshot) = {
    curTmpStateId += 1
    val oldHeap = heap
    val tmpHeapName = if (curTmpStateId == 0) "tmpHeap" else s"tmpHeap$curTmpStateId"
    heap = LocalVar(Identifier(tmpHeapName), heapTyp)
    val s = Assign(heap, oldHeap)
    (s, (curTmpStateId, oldHeap))
  }

  override def restoreState(s: StateSnapshot) {
    heap = s._2
    curTmpStateId = s._1 - 1
  }

  override def makeOldState: StateSnapshot = {
    curTmpStateId += 1
    val oldHeap = heap
    heap = Old(heap)
    (curTmpStateId, oldHeap)
  }

  override def checkDefinedness(e: sil.Exp, error: PartialVerificationError): Stmt = {
    e match {
      case sil.CurrentPerm(loc) =>
        Assert(translateExp(loc.rcv) !== nullLit, error.dueTo(reasons.ReceiverNull(loc)))
      case _ => Nil
    }
  }

  override def beginExhale: Stmt = {
    Havoc(exhaleHeap)
  }

  override def endExhale: Stmt = {
    Assume(FuncApp(identicalOnKnownLocsName, Seq(heap, exhaleHeap) ++ currentMask, Bool)) ++
      (heap := exhaleHeap)
  }
}
