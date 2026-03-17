package fcs.petri

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InvariantAnalysisSpec extends AnyFunSuite with Matchers:

  val net: PetriNet = FCSPetriNet.build(initialAmmo = 3)
  val stateSpace: StateSpaceResult = StateSpaceAnalyzer.explore(net)
  lazy val analysis: InvariantAnalysis.InvariantReport =
    InvariantAnalysis.fullAnalysis(net, stateSpace)

  // ─── Incidence Matrix ───

  test("Incidence matrix has correct dimensions"):
    val c = analysis.incidenceMatrix
    c.size shouldBe net.numPlaces
    c.foreach(_.size shouldBe net.numTransitions)

  test("Incidence matrix column sums match transition effects"):
    val c = analysis.incidenceMatrix
    // T0 (detect_target): P0=-1, P1=+1, P11=+1 (P10 cancels)
    c(FCSPetriNet.P_Idle)(0) shouldBe -1
    c(FCSPetriNet.P_TargetDetected)(0) shouldBe 1
    c(FCSPetriNet.P_AmmoStock)(0) shouldBe 0
    c(FCSPetriNet.P_KafkaQueue)(0) shouldBe 1

  // ─── P-Invariants ───

  test("P-invariants are computed (2 minimal)"):
    analysis.pInvariants should not be empty
    analysis.pInvariants.size shouldBe 2
    info(s"Found ${analysis.pInvariants.size} P-invariant(s)")
    analysis.pInvariants.foreach(inv => info(s"  $inv"))

  test("P-invariants satisfy y * C = 0"):
    val c = analysis.incidenceMatrix
    for inv <- analysis.pInvariants do
      for t <- 0 until net.numTransitions do
        val dotProduct = (0 until net.numPlaces).map(p => inv(p) * c(p)(t)).sum
        dotProduct shouldBe 0

  test("P-invariants are non-negative"):
    for inv <- analysis.pInvariants do
      inv.forall(_ >= 0) shouldBe true

  test("P-invariant confirms control flow conservation (INV7)"):
    // Expect an invariant with weight 1 on control flow places and 0 on resource places
    val controlFlowInv = analysis.pInvariants.find { inv =>
      inv(FCSPetriNet.P_Idle) > 0 &&
      inv(FCSPetriNet.P_Firing) > 0 &&
      inv(FCSPetriNet.P_AmmoStock) == 0 &&
      inv(FCSPetriNet.P_KafkaQueue) == 0 &&
      inv(FCSPetriNet.P_LogRecorded) == 0
    }
    controlFlowInv shouldBe defined
    info(s"Control flow P-invariant: ${controlFlowInv.get}")

  test("P-invariant value is constant across all reachable markings"):
    for inv <- analysis.pInvariants do
      val values = stateSpace.reachableMarkings.map { m =>
        inv.zip(m.tokens).map(_ * _).sum
      }
      values.size should be > 0
      values.forall(_ == values.head) shouldBe true

  // ─── T-Invariants ───

  test("T-invariants are computed"):
    analysis.tInvariants should not be empty
    info(s"Found ${analysis.tInvariants.size} T-invariant(s)")
    analysis.tInvariants.foreach(inv => info(s"  $inv"))

  test("T-invariants satisfy C * x = 0"):
    val c = analysis.incidenceMatrix
    for inv <- analysis.tInvariants do
      for p <- 0 until net.numPlaces do
        val dotProduct = (0 until net.numTransitions).map(t => c(p)(t) * inv(t)).sum
        dotProduct shouldBe 0

  test("T-invariants are non-negative"):
    for inv <- analysis.tInvariants do
      inv.forall(_ >= 0) shouldBe true

  test("Error cycle {T9, T10} is a T-invariant"):
    val errorInv = analysis.tInvariants.find { inv =>
      inv(9) > 0 && inv(10) > 0
    }
    errorInv shouldBe defined
    info(s"Error cycle T-invariant: ${errorInv.get}")

  // ─── Boundedness ───

  test("Network is bounded"):
    analysis.boundedness.isBounded shouldBe true
    info(s"Network is ${analysis.boundedness.k}-bounded")

  test("All place bounds are finite and non-negative"):
    analysis.boundedness.placeBounds.values.foreach { bound =>
      bound should be >= 0
    }

  test("Ammo stock bounded by initial value"):
    analysis.boundedness.placeBounds("Ammo_Stock") should be <= 3

  test("Control flow places are 1-bounded"):
    analysis.boundedness.placeBounds("Idle") should be <= 1
    analysis.boundedness.placeBounds("Firing") should be <= 1
    analysis.boundedness.placeBounds("Reloading") should be <= 1

  // ─── Liveness ───

  test("Network is not fully live (ammo exhaustion)"):
    analysis.liveness.isLive shouldBe false

  test("Error cycle transitions are live (L4)"):
    import InvariantAnalysis.LivenessLevel
    analysis.liveness.transitionLevels("error_detected") shouldBe LivenessLevel.L4
    analysis.liveness.transitionLevels("error_recovery") shouldBe LivenessLevel.L4

  test("Fire cycle transitions are at least L1"):
    import InvariantAnalysis.LivenessLevel
    analysis.liveness.transitionLevels("detect_target") shouldBe LivenessLevel.L1
    analysis.liveness.transitionLevels("fire") shouldBe LivenessLevel.L1
    analysis.liveness.transitionLevels("cooldown_complete") shouldBe LivenessLevel.L1

  test("No transitions are completely dead (L0) with ammo=3"):
    analysis.liveness.deadTransitions shouldBe empty

  // ─── Trace Comparison ───

  test("Nominal fire cycle executes completely"):
    val (trace, success) = TraceComparator.executePetriScenario(
      net, Vector(0, 1, 2, 3, 4, 5, 6, 7, 8)
    )
    success shouldBe true
    trace.size shouldBe 9
    trace.last.marking(FCSPetriNet.P_Idle) shouldBe 1

  test("Fire without authorization is blocked at T4"):
    val (trace, success) = TraceComparator.executePetriScenario(
      net, Vector(0, 1, 2, 4)
    )
    success shouldBe false
    trace.last.success shouldBe false
    trace.last.transitionName shouldBe "ready_sync"

  test("Error cycle completes successfully"):
    val (trace, success) = TraceComparator.executePetriScenario(
      net, Vector(9, 10)
    )
    success shouldBe true
    trace.last.marking(FCSPetriNet.P_Idle) shouldBe 1

  test("All comparison scenarios produce valid results"):
    val results = TraceComparator.runAllScenarios()
    results.size shouldBe 6
    results.foreach { r =>
      r.petriTrace should not be empty
      info(s"${r.name}: ${r.verdict}")
    }
