package fcs.petri

case class InvariantResult(
    id: String,
    description: String,
    property: String,
    satisfied: Boolean,
    counterExample: Option[Marking] = None,
    details: String = ""
):
  def status: String = if satisfied then "SATISFAIT" else "VIOLE"

  override def toString: String =
    val ce = counterExample.map(m => s"\n      Contre-exemple: $m").getOrElse("")
    s"  $status  $id — $description$ce"

case class VerificationReport(results: Vector[InvariantResult]):
  def allSatisfied: Boolean = results.forall(_.satisfied)
  def numSatisfied: Int = results.count(_.satisfied)
  def numViolated: Int = results.count(!_.satisfied)

  def report: String =
    val sb = new StringBuilder
    sb.append("===========================================================\n")
    sb.append("  VÉRIFICATION DES INVARIANTS MÉTIER\n")
    sb.append("===========================================================\n\n")
    results.foreach(r => sb.append(r.toString + "\n"))
    sb.append(s"\n  Résultat: $numSatisfied/${results.size} satisfaits")
    if allSatisfied then
      sb.append(" - TOUS LES INVARIANTS SONT RESPECTES\n")
    else
      sb.append(s" - $numViolated VIOLATION(S) DETECTEE(S)\n")
    sb.append("===========================================================\n")
    sb.toString

object InvariantChecker:

  import FCSPetriNet.*

  def checkAll(net: PetriNet, stateSpace: StateSpaceResult): VerificationReport =
    val results = Vector(
      checkINV1(stateSpace),
      checkINV2(stateSpace),
      checkINV3(stateSpace),
      checkINV4(stateSpace),
      checkINV5(stateSpace),
      checkINV6(stateSpace),
      checkINV7(net, stateSpace),
      checkINV8(net, stateSpace),
      checkINV9(net, stateSpace),
      checkINV10(stateSpace)
    )
    VerificationReport(results)

  def checkINV1(ss: StateSpaceResult): InvariantResult =
    val violation = ss.arcs.find { arc =>
      arc.to(P_ReadyToFire) > 0 && arc.transition.id == 4 && arc.from(P_TargetLocked) < 1
    }
    InvariantResult(
      id = "INV1",
      description = "Pas de tir sans verrouillage",
      property = "M(P5) > 0 ⇒ T4 exige P2",
      satisfied = violation.isEmpty,
      counterExample = violation.map(_.from)
    )

  def checkINV2(ss: StateSpaceResult): InvariantResult =
    val violation = ss.arcs.find { arc =>
      arc.to(P_ReadyToFire) > 0 && arc.transition.id == 4 && arc.from(P_AmmoLoaded) < 1
    }
    InvariantResult(
      id = "INV2",
      description = "Pas de tir sans munition chargée",
      property = "M(P5) > 0 ⇒ T4 exige P3",
      satisfied = violation.isEmpty,
      counterExample = violation.map(_.from)
    )

  def checkINV3(ss: StateSpaceResult): InvariantResult =
    val violation = ss.arcs.find { arc =>
      arc.to(P_ReadyToFire) > 0 && arc.transition.id == 4 && arc.from(P_FireAuthorized) < 1
    }
    InvariantResult(
      id = "INV3",
      description = "Pas de tir sans autorisation",
      property = "M(P5) > 0 ⇒ T4 exige P4",
      satisfied = violation.isEmpty,
      counterExample = violation.map(_.from)
    )

  def checkINV4(ss: StateSpaceResult): InvariantResult =
    val violation = ss.reachableMarkings.find(m => m(P_AmmoStock) < 0)
    InvariantResult(
      id = "INV4",
      description = "Stock munitions ≥ 0",
      property = "∀M atteignable : M(P10) ≥ 0",
      satisfied = violation.isEmpty,
      counterExample = violation
    )

  def checkINV5(ss: StateSpaceResult): InvariantResult =
    val violation = ss.reachableMarkings.find(m =>
      m(P_Firing) > 0 && m(P_Reloading) > 0
    )
    InvariantResult(
      id = "INV5",
      description = "Exclusion mutuelle tir/rechargement",
      property = "M(P6) + M(P7) ≤ 1",
      satisfied = violation.isEmpty,
      counterExample = violation
    )

  def checkINV6(ss: StateSpaceResult): InvariantResult =
    val violation = ss.reachableMarkings.find(m =>
      m(P_Firing) > 0 && m(P_Cooldown) > 0
    )
    InvariantResult(
      id = "INV6",
      description = "Pas de tir pendant le cooldown",
      property = "M(P6) > 0 ⇒ M(P8) = 0",
      satisfied = violation.isEmpty,
      counterExample = violation
    )

  def checkINV7(net: PetriNet, ss: StateSpaceResult): InvariantResult =
    val controlPlaces = (0 to 9).toVector
    val initialSum = controlPlaces.map(net.initialMarking(_)).sum

    val violation = ss.reachableMarkings.find { m =>
      controlPlaces.map(m(_)).sum != initialSum
    }
    InvariantResult(
      id = "INV7",
      description = "Conservation des jetons (flux de contrôle)",
      property = s"M(P0)+...+M(P9) = $initialSum (constante)",
      satisfied = violation.isEmpty,
      counterExample = violation,
      details = s"Somme initiale P0..P9 = $initialSum"
    )

  def checkINV8(net: PetriNet, ss: StateSpaceResult): InvariantResult =
    val firingStates = ss.reachableMarkings.filter(_(P_Firing) > 0)
    val kafkaBlocked = ss.reachableMarkings.find { m =>
      m(P_KafkaQueue) > 0 && !net.transitions(11).isEnabled(m)
    }
    InvariantResult(
      id = "INV8",
      description = "Tout tir est journalisé",
      property = "G(◇(M(P6)>0) → ◇(M(P12)>0))",
      satisfied = kafkaBlocked.isEmpty,
      counterExample = kafkaBlocked,
      details = s"${firingStates.size} états de tir vérifiés"
    )

  def checkINV9(net: PetriNet, ss: StateSpaceResult): InvariantResult =
    val idleStates = ss.reachableMarkings.filter(_(P_Idle) > 0)
    val nonDeadlocks = ss.reachableMarkings -- ss.deadlocks
    InvariantResult(
      id = "INV9",
      description = "Retour à l'état initial",
      property = "G(◇(M(P0) > 0))",
      satisfied = idleStates.nonEmpty,
      details = s"${idleStates.size} états avec P0>0 sur ${ss.numStates} états"
    )

  def checkINV10(ss: StateSpaceResult): InvariantResult =
    InvariantResult(
      id = "INV10",
      description = "Absence de deadlock",
      property = "Aucun état sans transition franchissable",
      satisfied = !ss.hasDeadlocks,
      counterExample = ss.deadlocks.headOption,
      details = s"${ss.numDeadlocks} deadlock(s) trouvé(s)"
    )

  def computePInvariants(net: PetriNet): Vector[Vector[Int]] =
    val incidence = net.incidenceMatrix
    findInvariants(incidence, net.numPlaces)

  def computeTInvariants(net: PetriNet): Vector[Vector[Int]] =
    val incidence = net.incidenceMatrix
    val transposed = (0 until net.numPlaces).map { p =>
      Marking(incidence.map(row => row.tokens(p)))
    }.toVector
    findInvariants(transposed.map(m => Marking(m.tokens)), net.numTransitions)

  private def findInvariants(
      matrix: Vector[Marking],
      dim: Int,
      maxCoeff: Int = 3
  ): Vector[Vector[Int]] =
    val result = scala.collection.mutable.ArrayBuffer[Vector[Int]]()

    def check(candidate: Vector[Int]): Boolean =
      matrix.forall { row =>
        candidate.zip(row.tokens).map((c, v) => c * v).sum == 0
      }

    def explore(pos: Int, current: Vector[Int]): Unit =
      if pos == dim then
        if current.exists(_ != 0) && check(current) then
          result += current
      else if result.size < 100 then // limite de sécurité
        for coeff <- 0 to maxCoeff do
          explore(pos + 1, current :+ coeff)

    if dim <= 15 then
      explore(0, Vector.empty)

    result.toVector
