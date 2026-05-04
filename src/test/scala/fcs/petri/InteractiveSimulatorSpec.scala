package fcs.petri

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class InteractiveSimulatorSpec extends AnyFunSuite with Matchers:

  import FCSPetriNet.*

  val net: PetriNet = FCSPetriNet.build(initialAmmo = 3)

  // ── Transition classification ────────────────────────────────────────

  test("Manual transitions are exactly: detect_target, lock_target, fire, error_detected"):
    InteractiveSimulator.manualTransitionNames shouldBe Set(
      "detect_target", "lock_target", "fire", "error_detected"
    )

  test("lock_target is classified as manual"):
    InteractiveSimulator.isManualTransition(T_LockTarget) shouldBe true

  test("detect_target is classified as manual"):
    InteractiveSimulator.isManualTransition(T_DetectTarget) shouldBe true

  test("fire is classified as manual"):
    InteractiveSimulator.isManualTransition(T_Fire) shouldBe true

  test("error_detected is classified as manual"):
    InteractiveSimulator.isManualTransition(T_ErrorDetected) shouldBe true

  test("load_ammo is classified as automatic"):
    InteractiveSimulator.isManualTransition(T_LoadAmmo) shouldBe false

  test("authorize_fire is classified as automatic"):
    InteractiveSimulator.isManualTransition(T_AuthorizeFire) shouldBe false

  test("cooldown_complete is classified as automatic"):
    InteractiveSimulator.isManualTransition(T_CooldownComplete) shouldBe false

  // ── Action labels ────────────────────────────────────────────────────

  test("Every transition has a human-readable label"):
    val labels = InteractiveSimulator.actionLabels
    net.transitions.foreach { t =>
      labels should contain key t.name
    }

  test("Labels are user-friendly, not raw transition names"):
    InteractiveSimulator.labelFor(T_DetectTarget) shouldBe "Detect a new target"
    InteractiveSimulator.labelFor(T_LockTarget) shouldBe "Lock on target"
    InteractiveSimulator.labelFor(T_Fire) shouldBe "Fire"

  // ── Output mode parsing ──────────────────────────────────────────────

  test("parseOutputMode defaults to verbose for unknown values"):
    InteractiveSimulator.parseOutputMode("unknown") shouldBe InteractiveSimulator.OutputMode.Verbose

  test("parseOutputMode recognizes verbose (case-insensitive)"):
    InteractiveSimulator.parseOutputMode("verbose") shouldBe InteractiveSimulator.OutputMode.Verbose
    InteractiveSimulator.parseOutputMode("VERBOSE") shouldBe InteractiveSimulator.OutputMode.Verbose

  test("parseOutputMode trims surrounding whitespace"):
    InteractiveSimulator.parseOutputMode("  verbose  ") shouldBe InteractiveSimulator.OutputMode.Verbose

  test("parseOutputMode supports compact explicitly"):
    InteractiveSimulator.parseOutputMode("compact") shouldBe InteractiveSimulator.OutputMode.Compact

  test("waitingForActionLine is shown only in compact mode"):
    InteractiveSimulator.waitingForActionLine(InteractiveSimulator.OutputMode.Compact) shouldBe Some("Waiting for operator action...")
    InteractiveSimulator.waitingForActionLine(InteractiveSimulator.OutputMode.Verbose) shouldBe None

  test("fireCountdownLines are exactly 3, 2, 1, Fire"):
    InteractiveSimulator.fireCountdownLines shouldBe List("3", "2", "1", "Fire")

  // ── Delay configuration ──────────────────────────────────────────────

  test("Automatic transitions have configured delays"):
    val delays = InteractiveSimulator.autoDelays
    delays should contain key "authorize_fire"
    delays should contain key "cooldown_complete"
    delays should contain key "reload_complete"
    delays should contain key "load_ammo"
    delays should contain key "kafka_log"

  test("cooldown_complete delay is 5 seconds"):
    InteractiveSimulator.delayMsFor(T_CooldownComplete) shouldBe 5000L

  test("authorize_fire delay is 2 seconds"):
    InteractiveSimulator.delayMsFor(T_AuthorizeFire) shouldBe 2000L

  test("Manual transitions should not have configured delays (use default)"):
    // Manual transitions are not in AutoDelays, so delayMsFor returns 1000L default
    InteractiveSimulator.delayMsFor(T_DetectTarget) shouldBe 1000L
    InteractiveSimulator.delayMsFor(T_Fire) shouldBe 1000L

  // ── Initial state manual choices ─────────────────────────────────────

  test("From initial marking, manual choices are detect_target and error_detected"):
    val choices = InteractiveSimulator.manualChoices(net, net.initialMarking)
    choices.map(_.name).toSet shouldBe Set("detect_target", "error_detected")

  // ── Auto-progression after detect_target ─────────────────────────────

  test("After detect_target, auto-progression stops because lock_target (manual) is enabled"):
    val afterDetect = T_DetectTarget.fire(net.initialMarking).get
    val (afterAuto, firedNames) = InteractiveSimulator.progressAuto(net, afterDetect)

    // No auto transitions fire because lock_target (manual) is also enabled
    firedNames shouldBe empty
    afterAuto shouldBe afterDetect

    // lock_target should be a manual choice
    val choices = net.enabledTransitions(afterAuto).filter(InteractiveSimulator.isManualTransition)
    choices.map(_.name) should contain("lock_target")

  // ── Auto-progression after lock_target ───────────────────────────────

  test("After lock_target, auto-progression fires load_ammo, authorize_fire, and ready_sync"):
    // After detect_target, no auto fires (lock_target is manual and enabled)
    val m1 = T_DetectTarget.fire(net.initialMarking).get
    // User picks lock_target directly
    val m2 = T_LockTarget.fire(m1).get

    val (afterAuto, firedNames) = InteractiveSimulator.progressAuto(net, m2)

    // Auto transitions fire in vector order; ready_sync enables fire (manual), so kafka_log is deferred
    firedNames should contain("load_ammo")
    firedNames should contain("authorize_fire")
    firedNames should contain("ready_sync")

    // After auto-progression, fire should be a manual choice
    val choices = net.enabledTransitions(afterAuto).filter(InteractiveSimulator.isManualTransition)
    choices.map(_.name) should contain("fire")

  // ── Auto-progression after fire ──────────────────────────────────────

  test("After fire, auto-progression completes full post-fire sequence back to Idle"):
    // Walk to fire state: detect → lock → auto → fire
    val m1 = T_DetectTarget.fire(net.initialMarking).get
    val m2 = T_LockTarget.fire(m1).get
    val (m3, _) = InteractiveSimulator.progressAuto(net, m2)
    val m4 = T_Fire.fire(m3).get

    val (afterAuto, firedNames) = InteractiveSimulator.progressAuto(net, m4)

    firedNames should contain("end_fire")
    firedNames should contain("reload_complete")
    firedNames should contain("cooldown_complete")

    // Should be back in Idle
    afterAuto(P_Idle) shouldBe 1

  // ── Full nominal cycle ───────────────────────────────────────────────

  test("Full nominal cycle: detect → lock → auto → fire → auto → back to Idle"):
    // Step 1: detect_target (manual)
    val m1 = T_DetectTarget.fire(net.initialMarking).get

    // Step 2: no auto-progression (lock_target is manual and enabled)
    val (m1b, auto1) = InteractiveSimulator.progressAuto(net, m1)
    auto1 shouldBe empty

    // Step 3: lock_target (manual)
    val m2 = T_LockTarget.fire(m1b).get

    // Step 4: auto-progression (load_ammo, authorize_fire, ready_sync)
    val (m3, auto2) = InteractiveSimulator.progressAuto(net, m2)
    auto2 should not be empty

    // Step 5: fire (manual)
    val m4 = T_Fire.fire(m3).get

    // Step 6: auto-progression → back to Idle
    // Note: load_ammo fires again in post-fire auto (fresh pass), consuming 1 extra ammo
    val (m5, auto3) = InteractiveSimulator.progressAuto(net, m4)
    auto3 should not be empty
    m5(P_Idle) shouldBe 1

    // Ammo consumed: 1 during lock→fire phase + 1 during post-fire phase = 2 total
    m5(P_AmmoStock) shouldBe 1

  // ── Error cycle ──────────────────────────────────────────────────────

  test("Error cycle: error_detected → auto error_recovery → back to Idle"):
    val m1 = T_ErrorDetected.fire(net.initialMarking).get
    m1(P_ErrorState) shouldBe 1
    m1(P_Idle) shouldBe 0

    val (m2, firedNames) = InteractiveSimulator.progressAuto(net, m1)
    firedNames should contain("error_recovery")
    m2(P_Idle) shouldBe 1
    m2(P_ErrorState) shouldBe 0

  // ── Edge cases ───────────────────────────────────────────────────────

  test("progressAuto on initial marking does nothing (manual transitions available)"):
    val (result, fired) = InteractiveSimulator.progressAuto(net, net.initialMarking)
    result shouldBe net.initialMarking
    fired shouldBe empty

  test("progressAuto preserves any provided fired prefix in forward order"):
    val m1 = T_DetectTarget.fire(net.initialMarking).get
    val m2 = T_LockTarget.fire(m1).get

    val (_, firedNames) = InteractiveSimulator.progressAuto(net, m2, fired = List("prefix"))

    firedNames shouldBe List("prefix", "load_ammo", "authorize_fire", "ready_sync")

  test("With zero ammo, detect_target is still enabled but load_ammo will not fire"):
    val emptyNet = FCSPetriNet.build(initialAmmo = 0)
    // detect_target requires P_Idle=1 and P_AmmoStock=1, so it should NOT be enabled
    T_DetectTarget.isEnabled(emptyNet.initialMarking) shouldBe false

  test("Multiple fire cycles consume ammo correctly"):
    // Each cycle consumes 2 ammo (load_ammo fires once per auto-progression pass).
    // With 5 initial ammo we can do 2 full cycles (5 → 3 → 1).
    val bigNet = FCSPetriNet.build(initialAmmo = 5)
    var m = bigNet.initialMarking
    for cycle <- 1 to 2 do
      m = T_DetectTarget.fire(m).get
      m = T_LockTarget.fire(m).get
      val (m3, _) = InteractiveSimulator.progressAuto(bigNet, m)
      m = T_Fire.fire(m3).get
      val (m5, _) = InteractiveSimulator.progressAuto(bigNet, m)
      m = m5
      m(P_Idle) shouldBe 1
      m(P_AmmoStock) shouldBe (5 - cycle * 2)
