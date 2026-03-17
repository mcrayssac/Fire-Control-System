package fcs.petri

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class FCSPetriNetSpec extends AnyFunSuite with Matchers:

  import FCSPetriNet.*

  val net: PetriNet = FCSPetriNet.build(initialAmmo = 3)

  test("FCS network has 13 places and 12 transitions"):
    net.numPlaces shouldBe 13
    net.numTransitions shouldBe 12

  test("Initial marking has 1 token in Idle and N in AmmoStock"):
    net.initialMarking(P_Idle) shouldBe 1
    net.initialMarking(P_AmmoStock) shouldBe 3
    (0 until 13).filter(i => i != P_Idle && i != P_AmmoStock).foreach { i =>
      net.initialMarking(i) shouldBe 0
    }

  test("Nominal fire cycle: T0 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8"):
    var m = net.initialMarking

    val afterT0 = net.transitions(0).fire(m)
    afterT0 shouldBe defined
    m = afterT0.get
    m(P_Idle) shouldBe 0
    m(P_TargetDetected) shouldBe 1
    m(P_KafkaQueue) shouldBe 1

    val afterT1 = net.transitions(1).fire(m)
    afterT1 shouldBe defined
    m = afterT1.get
    m(P_TargetLocked) shouldBe 1

    val afterT2 = net.transitions(2).fire(m)
    afterT2 shouldBe defined
    m = afterT2.get
    m(P_AmmoLoaded) shouldBe 1
    m(P_AmmoStock) shouldBe 2

    val afterT3 = net.transitions(3).fire(m)
    afterT3 shouldBe defined
    m = afterT3.get
    m(P_FireAuthorized) shouldBe 1
    m(P_TargetLocked) shouldBe 0

    val afterT4 = net.transitions(4).fire(m)
    afterT4 shouldBe defined
    m = afterT4.get
    m(P_ReadyToFire) shouldBe 1
    m(P_AmmoLoaded) shouldBe 0
    m(P_FireAuthorized) shouldBe 0

    val afterT5 = net.transitions(5).fire(m)
    afterT5 shouldBe defined
    m = afterT5.get
    m(P_Firing) shouldBe 1
    m(P_KafkaQueue) shouldBe 2

    val afterT6 = net.transitions(6).fire(m)
    afterT6 shouldBe defined
    m = afterT6.get
    m(P_Reloading) shouldBe 1
    m(P_Cooldown) shouldBe 0

    val afterT7 = net.transitions(7).fire(m)
    afterT7 shouldBe defined
    m = afterT7.get
    m(P_Reloading) shouldBe 0
    m(P_Cooldown) shouldBe 1

    val afterT8 = net.transitions(8).fire(m)
    afterT8 shouldBe defined
    m = afterT8.get
    m(P_Idle) shouldBe 1
    m(P_Cooldown) shouldBe 0

  test("T4 (sync) is not enabled without preconditions"):
    var m = net.initialMarking
    m = net.transitions(0).fire(m).get
    m = net.transitions(1).fire(m).get
    net.transitions(4).isEnabled(m) shouldBe false

  test("T2 (load_ammo) is blocked when ammo stock is empty"):
    val emptyNet = FCSPetriNet.build(initialAmmo = 0)
    emptyNet.transitions(2).isEnabled(emptyNet.initialMarking) shouldBe false

  test("T5 (fire) is not directly enabled from initial marking"):
    net.transitions(5).isEnabled(net.initialMarking) shouldBe false

  test("Error detection and recovery cycle: T9 → T10"):
    var m = net.initialMarking
    val afterT9 = net.transitions(9).fire(m)
    afterT9 shouldBe defined
    m = afterT9.get
    m(P_ErrorState) shouldBe 1
    m(P_Idle) shouldBe 0

    val afterT10 = net.transitions(10).fire(m)
    afterT10 shouldBe defined
    m = afterT10.get
    m(P_Idle) shouldBe 1
    m(P_ErrorState) shouldBe 0

  test("Kafka logging: T11 consumes from P11 and produces in P12"):
    val afterT0 = net.transitions(0).fire(net.initialMarking).get
    afterT0(P_KafkaQueue) shouldBe 1

    val afterT11 = net.transitions(11).fire(afterT0).get
    afterT11(P_KafkaQueue) shouldBe 0
    afterT11(P_LogRecorded) shouldBe 1
