package fcs.actors

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import fcs.model.*
import scala.concurrent.duration.*

class ActorSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  val testKit: ActorTestKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // ─── AmmoActor ───

  test("AmmoActor: loads ammo and replies with confirmation"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val ammo = testKit.spawn(AmmoActor(kafkaProbe.ref))

    val cycleId = FireCycleId()
    ammo ! AmmoProtocol.LoadAmmo(cycleId, AmmoType.APFSDS, replyProbe.ref)

    val reply = replyProbe.receiveMessage()
    reply shouldBe a[FireControlProtocol.AmmoLoadConfirmed]
    val confirmed = reply.asInstanceOf[FireControlProtocol.AmmoLoadConfirmed]
    confirmed.cycleId shouldBe cycleId
    confirmed.ammoType shouldBe AmmoType.APFSDS
    confirmed.remainingStock shouldBe 9

    kafkaProbe.receiveMessage() shouldBe a[KafkaMessages.PublishEvent]

  test("AmmoActor: fails when stock is exhausted"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val emptyStock = AmmoStock(stock = Map(
      AmmoType.APFSDS -> 0, AmmoType.HEAT -> 0,
      AmmoType.HESH -> 0, AmmoType.HE -> 0
    ))
    val ammo = testKit.spawn(AmmoActor(kafkaProbe.ref, emptyStock))

    ammo ! AmmoProtocol.LoadAmmo(FireCycleId(), AmmoType.APFSDS, replyProbe.ref)

    replyProbe.receiveMessage() shouldBe a[FireControlProtocol.AmmoLoadFailed]

  test("AmmoActor: stock decreases until exhaustion"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val smallStock = AmmoStock(stock = Map(
      AmmoType.APFSDS -> 2, AmmoType.HEAT -> 0,
      AmmoType.HESH -> 0, AmmoType.HE -> 0
    ))
    val ammo = testKit.spawn(AmmoActor(kafkaProbe.ref, smallStock))

    // First load: 2 -> 1
    ammo ! AmmoProtocol.LoadAmmo(FireCycleId(), AmmoType.APFSDS, replyProbe.ref)
    val r1 = replyProbe.receiveMessage().asInstanceOf[FireControlProtocol.AmmoLoadConfirmed]
    r1.remainingStock shouldBe 1
    kafkaProbe.receiveMessage()

    // Second load: 1 -> 0
    ammo ! AmmoProtocol.LoadAmmo(FireCycleId(), AmmoType.APFSDS, replyProbe.ref)
    val r2 = replyProbe.receiveMessage().asInstanceOf[FireControlProtocol.AmmoLoadConfirmed]
    r2.remainingStock shouldBe 0
    kafkaProbe.receiveMessage()

    // Third load: exhausted
    ammo ! AmmoProtocol.LoadAmmo(FireCycleId(), AmmoType.APFSDS, replyProbe.ref)
    replyProbe.receiveMessage() shouldBe a[FireControlProtocol.AmmoLoadFailed]

  // ─── CommandActor ───

  test("CommandActor: authorizes fire with good confidence"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val command = testKit.spawn(CommandActor(kafkaProbe.ref))

    val cycleId = FireCycleId()
    val goodSolution = BallisticSolution(5.0, 0.0, 1.5, confidence = 0.95)
    command ! CommandProtocol.RequestAuthorization(cycleId, goodSolution, replyProbe.ref)

    val reply = replyProbe.receiveMessage()
    reply shouldBe a[FireControlProtocol.FireAuthConfirmed]
    reply.asInstanceOf[FireControlProtocol.FireAuthConfirmed].cycleId shouldBe cycleId
    kafkaProbe.receiveMessage() shouldBe a[KafkaMessages.PublishEvent]

  test("CommandActor: denies fire with low confidence"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val command = testKit.spawn(CommandActor(kafkaProbe.ref))

    val badSolution = BallisticSolution(5.0, 0.0, 1.5, confidence = 0.3)
    command ! CommandProtocol.RequestAuthorization(FireCycleId(), badSolution, replyProbe.ref)

    val reply = replyProbe.receiveMessage()
    reply shouldBe a[FireControlProtocol.FireAuthDenied]
    val denied = reply.asInstanceOf[FireControlProtocol.FireAuthDenied]
    denied.reason should include("Confiance")

  // ─── TrackingActor ───

  test("TrackingActor: locks target within range"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val tracking = testKit.spawn(TrackingActor(kafkaProbe.ref))

    val cycleId = FireCycleId()
    val closeTarget = TargetCoordinates(1000, 200, 0, bearing = 45.0, range = 2000.0)
    tracking ! TrackingProtocol.TrackTarget(cycleId, closeTarget, replyProbe.ref)

    val reply = replyProbe.receiveMessage()
    reply shouldBe a[FireControlProtocol.TargetLockConfirmed]
    val locked = reply.asInstanceOf[FireControlProtocol.TargetLockConfirmed]
    locked.cycleId shouldBe cycleId
    locked.solution.confidence should be >= 0.5
    kafkaProbe.receiveMessage() shouldBe a[KafkaMessages.PublishEvent]

  test("TrackingActor: fails for target too far"):
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val replyProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val tracking = testKit.spawn(TrackingActor(kafkaProbe.ref))

    val farTarget = TargetCoordinates(5000, 0, 0, bearing = 180.0, range = 4500.0)
    tracking ! TrackingProtocol.TrackTarget(FireCycleId(), farTarget, replyProbe.ref)

    val reply = replyProbe.receiveMessage()
    reply shouldBe a[FireControlProtocol.TargetLockFailed]
    reply.asInstanceOf[FireControlProtocol.TargetLockFailed].reason should include("Confiance")

  // ─── FireControlActor ───

  test("FireControlActor: requests tracking and ammo on InitiateFireCycle"):
    val trackingProbe = testKit.createTestProbe[TrackingProtocol.Command]()
    val ammoProbe = testKit.createTestProbe[AmmoProtocol.Command]()
    val commandProbe = testKit.createTestProbe[CommandProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()

    val fc = testKit.spawn(
      FireControlActor(trackingProbe.ref, ammoProbe.ref, commandProbe.ref, kafkaProbe.ref)
    )
    val coords = TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    fc ! FireControlProtocol.InitiateFireCycle(coords)

    val trackMsg = trackingProbe.receiveMessage()
    trackMsg shouldBe a[TrackingProtocol.TrackTarget]

    val ammoMsg = ammoProbe.receiveMessage()
    ammoMsg shouldBe a[AmmoProtocol.LoadAmmo]

  test("FireControlActor: requests authorization after target lock"):
    val trackingProbe = testKit.createTestProbe[TrackingProtocol.Command]()
    val ammoProbe = testKit.createTestProbe[AmmoProtocol.Command]()
    val commandProbe = testKit.createTestProbe[CommandProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()

    val fc = testKit.spawn(
      FireControlActor(trackingProbe.ref, ammoProbe.ref, commandProbe.ref, kafkaProbe.ref)
    )
    fc ! FireControlProtocol.InitiateFireCycle(
      TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    )

    val trackMsg = trackingProbe.receiveMessage().asInstanceOf[TrackingProtocol.TrackTarget]
    ammoProbe.receiveMessage()
    val cycleId = trackMsg.cycleId

    val solution = BallisticSolution(5.0, 0.0, 1.5, 0.95)
    fc ! FireControlProtocol.TargetLockConfirmed(cycleId, solution)

    val cmdMsg = commandProbe.receiveMessage()
    cmdMsg shouldBe a[CommandProtocol.RequestAuthorization]

  test("FireControlActor: fires when all preconditions met"):
    val trackingProbe = testKit.createTestProbe[TrackingProtocol.Command]()
    val ammoProbe = testKit.createTestProbe[AmmoProtocol.Command]()
    val commandProbe = testKit.createTestProbe[CommandProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()

    val fc = testKit.spawn(
      FireControlActor(trackingProbe.ref, ammoProbe.ref, commandProbe.ref, kafkaProbe.ref)
    )
    fc ! FireControlProtocol.InitiateFireCycle(
      TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    )

    val trackMsg = trackingProbe.receiveMessage().asInstanceOf[TrackingProtocol.TrackTarget]
    ammoProbe.receiveMessage()
    val cycleId = trackMsg.cycleId
    val solution = BallisticSolution(5.0, 0.0, 1.5, 0.95)

    fc ! FireControlProtocol.TargetLockConfirmed(cycleId, solution)
    commandProbe.receiveMessage()

    fc ! FireControlProtocol.AmmoLoadConfirmed(cycleId, AmmoType.APFSDS, 9)
    fc ! FireControlProtocol.FireAuthConfirmed(cycleId)

    val kafkaEvent = kafkaProbe.receiveMessage().asInstanceOf[KafkaMessages.PublishEvent]
    kafkaEvent.topic shouldBe fcs.kafka.Topics.FireExecuted
    kafkaEvent.payload should include(cycleId.value)

  test("FireControlActor: aborts cycle when authorization denied"):
    val trackingProbe = testKit.createTestProbe[TrackingProtocol.Command]()
    val ammoProbe = testKit.createTestProbe[AmmoProtocol.Command]()
    val commandProbe = testKit.createTestProbe[CommandProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()

    val fc = testKit.spawn(
      FireControlActor(trackingProbe.ref, ammoProbe.ref, commandProbe.ref, kafkaProbe.ref)
    )
    fc ! FireControlProtocol.InitiateFireCycle(
      TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    )

    val trackMsg = trackingProbe.receiveMessage().asInstanceOf[TrackingProtocol.TrackTarget]
    ammoProbe.receiveMessage()
    val cycleId = trackMsg.cycleId

    fc ! FireControlProtocol.TargetLockConfirmed(
      cycleId, BallisticSolution(5.0, 0.0, 1.5, 0.95)
    )
    commandProbe.receiveMessage()

    fc ! FireControlProtocol.FireAuthDenied(cycleId, "ROE non respectees")

    val kafkaEvent = kafkaProbe.receiveMessage().asInstanceOf[KafkaMessages.PublishEvent]
    kafkaEvent.topic shouldBe fcs.kafka.Topics.ErrorCritical

  test("FireControlActor: aborts cycle when target lock fails"):
    val trackingProbe = testKit.createTestProbe[TrackingProtocol.Command]()
    val ammoProbe = testKit.createTestProbe[AmmoProtocol.Command]()
    val commandProbe = testKit.createTestProbe[CommandProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()

    val fc = testKit.spawn(
      FireControlActor(trackingProbe.ref, ammoProbe.ref, commandProbe.ref, kafkaProbe.ref)
    )
    fc ! FireControlProtocol.InitiateFireCycle(
      TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    )

    val trackMsg = trackingProbe.receiveMessage().asInstanceOf[TrackingProtocol.TrackTarget]
    ammoProbe.receiveMessage()

    fc ! FireControlProtocol.TargetLockFailed(trackMsg.cycleId, "Confiance insuffisante")

    val kafkaEvent = kafkaProbe.receiveMessage().asInstanceOf[KafkaMessages.PublishEvent]
    kafkaEvent.topic shouldBe fcs.kafka.Topics.ErrorCritical

  test("FireControlActor: aborts cycle when ammo loading fails"):
    val trackingProbe = testKit.createTestProbe[TrackingProtocol.Command]()
    val ammoProbe = testKit.createTestProbe[AmmoProtocol.Command]()
    val commandProbe = testKit.createTestProbe[CommandProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()

    val fc = testKit.spawn(
      FireControlActor(trackingProbe.ref, ammoProbe.ref, commandProbe.ref, kafkaProbe.ref)
    )
    fc ! FireControlProtocol.InitiateFireCycle(
      TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    )

    trackingProbe.receiveMessage()
    val ammoMsg = ammoProbe.receiveMessage().asInstanceOf[AmmoProtocol.LoadAmmo]

    fc ! FireControlProtocol.AmmoLoadFailed(ammoMsg.cycleId)

    val kafkaEvent = kafkaProbe.receiveMessage().asInstanceOf[KafkaMessages.PublishEvent]
    kafkaEvent.topic shouldBe fcs.kafka.Topics.ErrorCritical

  // ─── SensorActor ───

  test("SensorActor: sends InitiateFireCycle on detection"):
    val fcProbe = testKit.createTestProbe[FireControlProtocol.Command]()
    val kafkaProbe = testKit.createTestProbe[KafkaMessages.KafkaCommand]()
    val sensor = testKit.spawn(SensorActor(fcProbe.ref, kafkaProbe.ref))

    val coords = TargetCoordinates(1000, 200, 0, 45.0, 2000.0)
    sensor ! SensorProtocol.SimulateDetection(coords)

    val fcMsg = fcProbe.receiveMessage()
    fcMsg shouldBe a[FireControlProtocol.InitiateFireCycle]
    fcMsg.asInstanceOf[FireControlProtocol.InitiateFireCycle].coordinates shouldBe coords
    kafkaProbe.receiveMessage() shouldBe a[KafkaMessages.PublishEvent]
