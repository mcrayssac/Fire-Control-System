package fcs.petri

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class VerificationSpec extends AnyFunSuite with Matchers:

  val net: PetriNet = FCSPetriNet.buildWithReadArc(initialAmmo = 2)
  val stateSpace: StateSpaceResult = StateSpaceAnalyzer.explore(net)

  test("State space exploration terminates"):
    stateSpace.numStates should be > 0
    info(s"States explored: ${stateSpace.numStates}")
    info(s"Arcs: ${stateSpace.numArcs}")

  test("Initial marking is in reachable set"):
    stateSpace.reachableMarkings should contain(net.initialMarking)

  test("Firing state is reachable"):
    val firingStates = stateSpace.reachableMarkings.filter(_(FCSPetriNet.P_Firing) > 0)
    firingStates should not be empty
    info(s"Firing states found: ${firingStates.size}")

  test("INV1: No fire without target lock"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV1").get.satisfied shouldBe true

  test("INV2: No fire without ammo loaded"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV2").get.satisfied shouldBe true

  test("INV3: No fire without authorization"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV3").get.satisfied shouldBe true

  test("INV4: Ammo stock never negative"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV4").get.satisfied shouldBe true

  test("INV5: Mutual exclusion firing/reloading"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV5").get.satisfied shouldBe true

  test("INV6: No firing during cooldown"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV6").get.satisfied shouldBe true

  test("INV8: Every fire event is logged"):
    val report = InvariantChecker.checkAll(net, stateSpace)
    report.results.find(_.id == "INV8").get.satisfied shouldBe true

  test("LTL: G(¬(firing ∧ reloading))"):
    val results = LTLVerifier.verifyAll(net, stateSpace)
    results.find(_.formula.contains("firing ∧ reloading")).get.satisfied shouldBe true

  test("LTL: G(¬(ammo < 0))"):
    val results = LTLVerifier.verifyAll(net, stateSpace)
    results.find(_.formula.contains("ammo")).get.satisfied shouldBe true

  test("LTL: G(cooldown → ¬firing)"):
    val results = LTLVerifier.verifyAll(net, stateSpace)
    results.find(_.formula.contains("cooldown")).get.satisfied shouldBe true

  test("Path to firing state exists"):
    val path = StateSpaceAnalyzer.findPath(
      net,
      target = m => m(FCSPetriNet.P_Firing) > 0
    )
    path shouldBe defined
    info(s"Path length: ${path.get.size} transitions")
    info(s"Path: ${path.get.map(_.name).mkString(" → ")}")

  test("No path to negative ammo"):
    val path = StateSpaceAnalyzer.findMarking(
      net,
      predicate = m => m(FCSPetriNet.P_AmmoStock) < 0
    )
    path shouldBe None

  test("System handles ammo exhaustion gracefully"):
    val smallNet = FCSPetriNet.buildWithReadArc(initialAmmo = 1)
    val smallSS = StateSpaceAnalyzer.explore(smallNet)

    val noAmmoStates = smallSS.reachableMarkings.filter(_(FCSPetriNet.P_AmmoStock) == 0)
    noAmmoStates should not be empty

    noAmmoStates.foreach { m =>
      m(FCSPetriNet.P_AmmoStock) should be >= 0
    }
