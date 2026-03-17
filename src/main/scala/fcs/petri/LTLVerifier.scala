package fcs.petri

import scala.collection.mutable

type AtomicProp = Marking => Boolean

enum LTLFormula:
  case Atom(name: String, prop: AtomicProp)
  case Not(f: LTLFormula)
  case And(f1: LTLFormula, f2: LTLFormula)
  case Or(f1: LTLFormula, f2: LTLFormula)
  case Implies(f1: LTLFormula, f2: LTLFormula)
  case Globally(f: LTLFormula)
  case Finally(f: LTLFormula)
  case Until(f1: LTLFormula, f2: LTLFormula)
  case Next(f: LTLFormula)

case class LTLResult(
    formula: String,
    satisfied: Boolean,
    description: String,
    counterExamplePath: Option[Vector[Marking]] = None
):
  def status: String = if satisfied then "OK" else "FAIL"
  override def toString: String =
    val ce = counterExamplePath.map(p =>
      s"\n      Contre-exemple (${p.size} états): ${p.take(3).map(_.toString).mkString(" → ")}..."
    ).getOrElse("")
    s"  $status  $formula\n      $description$ce"

object LTLVerifier:

  import LTLFormula.*
  import FCSPetriNet.*

  val idle: LTLFormula         = Atom("idle", m => m(P_Idle) > 0)
  val firing: LTLFormula       = Atom("firing", m => m(P_Firing) > 0)
  val reloading: LTLFormula    = Atom("reloading", m => m(P_Reloading) > 0)
  val cooldown: LTLFormula     = Atom("cooldown", m => m(P_Cooldown) > 0)
  val targetLocked: LTLFormula = Atom("target_locked", m => m(P_TargetLocked) > 0)
  val ammoLoaded: LTLFormula   = Atom("ammo_loaded", m => m(P_AmmoLoaded) > 0)
  val fireAuth: LTLFormula     = Atom("fire_authorized", m => m(P_FireAuthorized) > 0)
  val readyToFire: LTLFormula  = Atom("ready_to_fire", m => m(P_ReadyToFire) > 0)
  val errorState: LTLFormula   = Atom("error_state", m => m(P_ErrorState) > 0)
  val logRecorded: LTLFormula  = Atom("log_recorded", m => m(P_LogRecorded) > 0)
  val kafkaQueue: LTLFormula   = Atom("kafka_queue", m => m(P_KafkaQueue) > 0)
  val ammoNonNeg: LTLFormula   = Atom("ammo_stock >= 0", m => m(P_AmmoStock) >= 0)

  val prop_NoFireAndReload: LTLFormula =
    Globally(Not(And(firing, reloading)))

  val prop_FireImpliesLog: LTLFormula =
    Globally(Implies(firing, Finally(logRecorded)))

  val prop_ErrorRecovery: LTLFormula =
    Globally(Implies(errorState, Finally(idle)))

  val prop_Fairness: LTLFormula =
    Globally(Finally(idle))

  val prop_AmmoNonNegative: LTLFormula =
    Globally(ammoNonNeg)

  val prop_NoCooldownFiring: LTLFormula =
    Globally(Implies(cooldown, Not(firing)))

  val allProperties: Vector[(String, LTLFormula)] = Vector(
    ("G(¬(firing ∧ reloading))", prop_NoFireAndReload),
    ("G(fire → F(log_recorded))", prop_FireImpliesLog),
    ("G(error → F(idle))", prop_ErrorRecovery),
    ("G(F(idle))", prop_Fairness),
    ("G(¬(ammo < 0))", prop_AmmoNonNegative),
    ("G(cooldown → ¬firing)", prop_NoCooldownFiring)
  )

  def verifyAll(net: PetriNet, ss: StateSpaceResult): Vector[LTLResult] =
    allProperties.map { (name, formula) =>
      verify(net, ss, name, formula)
    }

  def verify(
      net: PetriNet,
      ss: StateSpaceResult,
      name: String,
      formula: LTLFormula
  ): LTLResult =
    val description = describeFormula(formula)

    formula match
      case Globally(Implies(f1, Finally(f2))) =>
        val targetStates = ss.reachableMarkings.filter(m => evaluate(f2, m, net, ss))
        val canReachTarget = backwardReachable(targetStates, ss)
        val violating = ss.reachableMarkings.find(m =>
          evaluate(f1, m, net, ss) && !canReachTarget.contains(m)
        )
        LTLResult(
          formula = name,
          satisfied = violating.isEmpty,
          description = description,
          counterExamplePath = violating.map(m => Vector(m))
        )

      case Globally(Finally(inner)) =>
        val targetStates = ss.reachableMarkings.filter(m => evaluate(inner, m, net, ss))
        val canReachTarget = backwardReachable(targetStates, ss)
        val violating = ss.reachableMarkings.find(m => !canReachTarget.contains(m))
        LTLResult(
          formula = name,
          satisfied = violating.isEmpty,
          description = description,
          counterExamplePath = violating.map(m => Vector(m))
        )

      case Globally(inner) =>
        val violating = ss.reachableMarkings.find(m => !evaluate(inner, m, net, ss))
        LTLResult(
          formula = name,
          satisfied = violating.isEmpty,
          description = description,
          counterExamplePath = violating.map(m => Vector(m))
        )

      case Finally(inner) =>
        val satisfying = ss.reachableMarkings.find(m => evaluate(inner, m, net, ss))
        LTLResult(
          formula = name,
          satisfied = satisfying.isDefined,
          description = description
        )

      case _ =>
        val result = evaluate(formula, ss.initialMarking, net, ss)
        LTLResult(
          formula = name,
          satisfied = result,
          description = description
        )

  private def evaluate(
      formula: LTLFormula,
      marking: Marking,
      net: PetriNet,
      ss: StateSpaceResult
  ): Boolean =
    formula match
      case Atom(_, prop) =>
        prop(marking)

      case Not(f) =>
        !evaluate(f, marking, net, ss)

      case And(f1, f2) =>
        evaluate(f1, marking, net, ss) && evaluate(f2, marking, net, ss)

      case Or(f1, f2) =>
        evaluate(f1, marking, net, ss) || evaluate(f2, marking, net, ss)

      case Implies(f1, f2) =>
        !evaluate(f1, marking, net, ss) || evaluate(f2, marking, net, ss)

      case Globally(f) =>
        reachableFrom(marking, ss).forall(m => evaluate(f, m, net, ss))

      case Finally(f) =>
        reachableFrom(marking, ss).exists(m => evaluate(f, m, net, ss))

      case Until(f1, f2) =>
        evaluate(Finally(f2), marking, net, ss)

      case Next(f) =>
        ss.successorMap.getOrElse(marking, Vector.empty)
          .forall(m => evaluate(f, m, net, ss))

  private def backwardReachable(targets: Set[Marking], ss: StateSpaceResult): Set[Marking] =
    val visited = mutable.Set.from(targets)
    val queue   = mutable.Queue.from(targets)
    val preds   = ss.predecessorMap

    while queue.nonEmpty do
      val current = queue.dequeue()
      for prev <- preds.getOrElse(current, Vector.empty) do
        if !visited.contains(prev) then
          visited.add(prev)
          queue.enqueue(prev)

    visited.toSet

  private def reachableFrom(marking: Marking, ss: StateSpaceResult): Set[Marking] =
    val visited = mutable.Set[Marking]()
    val queue   = mutable.Queue[Marking]()
    val succs   = ss.successorMap

    queue.enqueue(marking)
    visited.add(marking)

    while queue.nonEmpty do
      val current = queue.dequeue()
      for next <- succs.getOrElse(current, Vector.empty) do
        if !visited.contains(next) then
          visited.add(next)
          queue.enqueue(next)

    visited.toSet

  private def describeFormula(formula: LTLFormula): String =
    formula match
      case Atom(name, _) => name
      case Not(f)        => s"¬(${describeFormula(f)})"
      case And(f1, f2)   => s"(${describeFormula(f1)} ∧ ${describeFormula(f2)})"
      case Or(f1, f2)    => s"(${describeFormula(f1)} ∨ ${describeFormula(f2)})"
      case Implies(f1, f2) => s"(${describeFormula(f1)} → ${describeFormula(f2)})"
      case Globally(f)   => s"G(${describeFormula(f)})"
      case Finally(f)    => s"F(${describeFormula(f)})"
      case Until(f1, f2) => s"(${describeFormula(f1)} U ${describeFormula(f2)})"
      case Next(f)       => s"X(${describeFormula(f)})"

  def report(results: Vector[LTLResult]): String =
    val sb = new StringBuilder
    sb.append("===========================================================\n")
    sb.append("  VÉRIFICATION LTL (Linear Temporal Logic)\n")
    sb.append("===========================================================\n\n")
    results.foreach(r => sb.append(r.toString + "\n\n"))

    val satisfied = results.count(_.satisfied)
    val total = results.size
    sb.append(s"  Résultat: $satisfied/$total propriétés satisfaites")
    if satisfied == total then
      sb.append(" - TOUTES LES PROPRIETES LTL SONT VERIFIEES\n")
    else
      sb.append(s" - ${total - satisfied} VIOLATION(S)\n")
    sb.append("===========================================================\n")
    sb.toString
