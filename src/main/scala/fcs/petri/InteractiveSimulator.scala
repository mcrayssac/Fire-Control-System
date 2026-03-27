package fcs.petri

import scala.annotation.tailrec
import scala.io.StdIn

object InteractiveSimulator:

  enum OutputMode:
    case Compact, Verbose

  // ── Transition metadata ──────────────────────────────────────────────

  /** Human-readable label for each transition (by name). */
  private val ActionLabels: Map[String, String] = Map(
    "detect_target"      -> "Detect a new target",
    "lock_target"        -> "Lock on target",
    "load_ammo"          -> "Load ammunition",
    "authorize_fire"     -> "Authorize fire",
    "ready_sync"         -> "Synchronize fire solution",
    "fire"               -> "Fire",
    "end_fire"           -> "End fire sequence",
    "reload_complete"    -> "Reload",
    "cooldown_complete"  -> "Cooldown in progress",
    "error_detected"     -> "Report system error",
    "error_recovery"     -> "Recover from error",
    "kafka_log"          -> "Record event log"
  )

  /** Manual transitions require user input; everything else is automatic. */
  private val ManualTransitions: Set[String] = Set(
    "detect_target",
    "lock_target",
    "fire",
    "error_detected"
  )

  /** Simulated delay in milliseconds for each automatic transition. */
  private val AutoDelays: Map[String, Long] = Map(
    "load_ammo"         -> 2000L,
    "authorize_fire"    -> 2000L,
    "ready_sync"        -> 1000L,
    "end_fire"          -> 2000L,
    "reload_complete"   -> 4000L,
    "cooldown_complete" -> 5000L,
    "error_recovery"    -> 3000L,
    "kafka_log"         -> 500L
  )

  // ── Public API (testable) ────────────────────────────────────────────

  /** Parse interactive output mode from user input. */
  def parseOutputMode(raw: String): OutputMode =
    raw.trim.toLowerCase match
      case "compact" => OutputMode.Compact
      case _         => OutputMode.Verbose

  /** Get the human-readable label for a transition. */
  def labelFor(t: Transition): String =
    ActionLabels.getOrElse(t.name, t.name)

  /** Check whether a transition is classified as manual (user-triggered). */
  def isManualTransition(t: Transition): Boolean =
    ManualTransitions.contains(t.name)

  /** Get the configured delay in ms for an automatic transition. */
  def delayMsFor(t: Transition): Long =
    AutoDelays.getOrElse(t.name, 1000L)

  /** Get the set of manual transition names. */
  def manualTransitionNames: Set[String] = ManualTransitions

  /** Line shown when UI waits for operator input (compact mode). */
  def waitingForActionLine(mode: OutputMode): Option[String] =
    if mode == OutputMode.Compact then Some("Waiting for operator action...")
    else None

  /** Countdown lines shown before firing. */
  def fireCountdownLines: List[String] =
    List("3", "2", "1", "Fire")

  /** Get the action labels map. */
  def actionLabels: Map[String, String] = ActionLabels

  /** Get the auto delays map. */
  def autoDelays: Map[String, Long] = AutoDelays

  /**
   * Fire all enabled automatic transitions from the given marking,
   * without delays (for testing). Each transition fires at most once
   * per auto-progression pass. Returns the final marking and the
   * list of transition names that were fired.
   */
  def progressAuto(net: PetriNet, marking: Marking, fired: List[String] = Nil): (Marking, List[String]) =
    progressAutoLoop(net, marking, fired, Set.empty)

  @tailrec
  private def progressAutoLoop(
      net: PetriNet,
      marking: Marking,
      fired: List[String],
      alreadyFired: Set[String]
  ): (Marking, List[String]) =
    val enabled = net.enabledTransitions(marking)
    val autoEnabled = enabled.filter(t => !ManualTransitions.contains(t.name) && !alreadyFired.contains(t.name))
    val manualEnabled = enabled.filter(t => ManualTransitions.contains(t.name))
    if autoEnabled.isEmpty || manualEnabled.nonEmpty then
      (marking, fired.reverse)
    else
      val t = autoEnabled.head
      t.fire(marking) match
        case Some(newMarking) =>
          progressAutoLoop(net, newMarking, t.name :: fired, alreadyFired + t.name)
        case None =>
          (marking, fired.reverse)

  /**
   * Return the list of manual transitions enabled from the given marking
   * (after auto-progression).
   */
  def manualChoices(net: PetriNet, marking: Marking): Vector[Transition] =
    val (afterAuto, _) = progressAuto(net, marking)
    net.enabledTransitions(afterAuto).filter(t => ManualTransitions.contains(t.name))

  // ── Private helpers ──────────────────────────────────────────────────

  private def label(t: Transition): String = labelFor(t)

  private def labelByName(name: String): String =
    ActionLabels.getOrElse(name, name)

  private def isManual(t: Transition): Boolean = isManualTransition(t)

  private def delayMs(t: Transition): Long = delayMsFor(t)

  private def ammoCount(m: Marking): Int =
    m.tokens(FCSPetriNet.P_AmmoStock)

  private def isIdle(m: Marking): Boolean =
    m.tokens(FCSPetriNet.P_Idle) > 0

  private def inError(m: Marking): Boolean =
    m.tokens(FCSPetriNet.P_ErrorState) > 0

  private def readyToFire(m: Marking): Boolean =
    m.tokens(FCSPetriNet.P_ReadyToFire) > 0

  private def stateSummary(m: Marking): String =
    if inError(m) then "ERROR"
    else if m.tokens(FCSPetriNet.P_Firing) > 0 then "FIRING"
    else if readyToFire(m) then "READY"
    else if m.tokens(FCSPetriNet.P_TargetLocked) > 0 || m.tokens(FCSPetriNet.P_FireAuthorized) > 0 || m.tokens(FCSPetriNet.P_AmmoLoaded) > 0 then "ENGAGING"
    else if m.tokens(FCSPetriNet.P_TargetDetected) > 0 then "TRACKING"
    else if isIdle(m) then "IDLE"
    else "TRANSIT"

  private def printStatus(m: Marking, cycle: Int): Unit =
    println("\n── STATUS ──")
    println(s"Cycle: #$cycle")
    println(s"State: ${stateSummary(m)}")
    println(s"Ammo stock: ${ammoCount(m)}")
    println(s"Ready to fire: ${if readyToFire(m) then "YES" else "NO"}")
    println(s"Error state: ${if inError(m) then "YES" else "NO"}")

  private def printLowAmmoWarning(ammo: Int, lastWarnedAmmo: Option[Int]): Option[Int] =
    if ammo <= 1 then
      if lastWarnedAmmo.contains(ammo) then lastWarnedAmmo
      else
        println(s"Warning: low ammo stock ($ammo)")
        Some(ammo)
    else
      None

  private def simulateDelayVerbose(t: Transition): Unit =
    val ms = delayMs(t)
    val seconds = ms / 1000.0
    val lbl = label(t)
    println(f"Starting system action: $lbl (expected ${seconds}%.1fs)")
    Thread.sleep(ms)
    println(s"Completed system action: $lbl")

  private def printAutoSummary(fired: List[String], mode: OutputMode): Unit =
    if fired.nonEmpty then
      println("\n── AUTO ACTIONS ──")
      val labels = fired.map(labelByName)
      println(s"Completed: ${labels.mkString(" -> ")}")
      if mode == OutputMode.Verbose then
        println(s"Auto transition count: ${fired.size}")

  private def runFireCountdown(mode: OutputMode): Unit =
    if mode == OutputMode.Compact then
      println("Firing in:")
    else
      println("Initiating fire countdown:")
    fireCountdownLines.foreach { line =>
      println(line)
      if line != "Fire" then Thread.sleep(1000L)
    }

  // ── Core loop ────────────────────────────────────────────────────────

  def run(net: PetriNet): Unit =
    run(net, OutputMode.Verbose)

  def run(net: PetriNet, mode: OutputMode): Unit =
    println()
    println("FIRE CONTROL SYSTEM - Interactive Command Panel")
    println("=" * 52)
    println(s"Initial ammunition stock: ${ammoCount(net.initialMarking)} rounds")
    println(s"Output mode: ${mode.toString.toLowerCase}")
    println()
    println("Commands: <action number> | status | help | reset | quit")
    loop(net, net.initialMarking, nextCycle = 1, lastWarnedAmmo = None, mode)

  @tailrec
  private def loop(
      net: PetriNet,
      marking: Marking,
      nextCycle: Int,
      lastWarnedAmmo: Option[Int],
      mode: OutputMode
  ): Unit =
    // Run automatic transitions first
    val (afterAuto, autoFired) = runAutoTransitions(net, marking, mode)
    printAutoSummary(autoFired, mode)

    // True cycle completion: system returned to Idle from an active state
    val cycleCompleted = isIdle(afterAuto) && !isIdle(marking) && afterAuto != net.initialMarking
    val cycleForDisplay =
      if cycleCompleted then
        println(s"\nCycle #$nextCycle completed. System ready for next engagement.")
        nextCycle + 1
      else nextCycle

    printStatus(afterAuto, cycleForDisplay)
    val warnedNow = printLowAmmoWarning(ammoCount(afterAuto), lastWarnedAmmo)

    val enabled = net.enabledTransitions(afterAuto)
    if enabled.isEmpty then
      println("\nDeadlock detected: no transitions available. Resetting system...")
      loop(net, net.initialMarking, cycleForDisplay, None, mode)
    else
      val manualEnabled = enabled.filter(isManual)
      val autoEnabled = enabled.filter(t => !isManual(t))

      // If there are only auto transitions left, fire them and re-loop
      if manualEnabled.isEmpty && autoEnabled.nonEmpty then
        loop(net, afterAuto, cycleForDisplay, warnedNow, mode)
      else
        // Display manual actions to user
        println("\n── MANUAL ACTIONS ──")
        manualEnabled.zipWithIndex.foreach { (t, idx) =>
          println(s"[${idx + 1}] ${label(t)}")
        }

        println("\n── INPUT ──")
        waitingForActionLine(mode).foreach(println)
        print("Action number | status | help | reset | quit > ")
        System.out.flush()

        val input = StdIn.readLine()
        if input == null then
          println("\nInput stream closed (EOF).")
          println("If this was unexpected, run from an interactive terminal:")
          println("  1) sbt")
          println("  2) run live")
          println("\nSystem shutdown. Goodbye.")
        else
          input.trim.toLowerCase match
            case "quit" | "exit" | "q" =>
              println("\nSystem shutdown. Goodbye.")

            case "status" =>
              printStatus(afterAuto, cycleForDisplay)
              loop(net, afterAuto, cycleForDisplay, warnedNow, mode)

            case "help" =>
              println("""
                |Commands:
                |  <number>  - Execute the action with that number
                |  status    - Show current system status
                |  reset     - Reset system to initial state
                |  quit      - Exit the simulation
                |""".stripMargin)
              loop(net, afterAuto, cycleForDisplay, warnedNow, mode)

            case "reset" =>
              println("\nSystem reset to initial state.")
              loop(net, net.initialMarking, cycleForDisplay, None, mode)

            case other =>
              other.toIntOption match
                case Some(n) if n >= 1 && n <= manualEnabled.size =>
                  val chosen = manualEnabled(n - 1)
                  println(s"\nExecuting user action: ${label(chosen)}")
                  if chosen.name == "fire" then runFireCountdown(mode)
                  chosen.fire(afterAuto) match
                    case Some(newMarking) =>
                      println(s"Action completed: ${label(chosen)}")
                      loop(net, newMarking, cycleForDisplay, warnedNow, mode)
                    case None =>
                      println(s"Failed to execute action: ${label(chosen)}")
                      loop(net, afterAuto, cycleForDisplay, warnedNow, mode)
                case _ =>
                  println(s"Invalid input '$other'. Please enter a valid action number.")
                  loop(net, afterAuto, cycleForDisplay, warnedNow, mode)

  /** Fire all enabled automatic transitions in sequence with delays.
    * Each transition fires at most once per pass.
    */
  private def runAutoTransitions(net: PetriNet, marking: Marking, mode: OutputMode): (Marking, List[String]) =
    runAutoLoop(net, marking, Set.empty, Nil, mode)

  @tailrec
  private def runAutoLoop(
      net: PetriNet,
      marking: Marking,
      alreadyFired: Set[String],
      fired: List[String],
      mode: OutputMode
  ): (Marking, List[String]) =
    val enabled = net.enabledTransitions(marking)
    val autoEnabled = enabled.filter(t => !isManual(t) && !alreadyFired.contains(t.name))
    val manualEnabled = enabled.filter(isManual)

    if autoEnabled.isEmpty || manualEnabled.nonEmpty then
      (marking, fired.reverse)
    else
      val t = autoEnabled.head
      t.fire(marking) match
        case Some(newMarking) =>
          if mode == OutputMode.Verbose then simulateDelayVerbose(t)
          else Thread.sleep(delayMs(t))
          runAutoLoop(net, newMarking, alreadyFired + t.name, t.name :: fired, mode)
        case None =>
          (marking, fired.reverse)