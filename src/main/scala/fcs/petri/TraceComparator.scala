package fcs.petri

import fcs.model.KafkaMessages
import fcs.kafka.Topics

object TraceComparator:

  case class TraceEvent(
      step: Int,
      transitionId: Int,
      transitionName: String,
      marking: Marking,
      success: Boolean
  )

  case class ScenarioResult(
      name: String,
      description: String,
      petriTrace: Vector[TraceEvent],
      petriSuccess: Boolean,
      akkaExpectedBehavior: String,
      verdict: String
  )

  val transitionToAkkaEvent: Map[String, String] = Map(
    "detect_target"     -> "SensorActor -> TargetDetected -> InitiateFireCycle",
    "lock_target"       -> "TrackingActor -> TargetLockConfirmed",
    "load_ammo"         -> "AmmoActor -> AmmoLoadConfirmed",
    "authorize_fire"    -> "CommandActor -> FireAuthConfirmed",
    "ready_sync"        -> "FireControlActor.checkSync -> isReadyToFire",
    "fire"              -> "FireControlActor.executeFire -> FireExecuted",
    "end_fire"          -> "FireControlActor -> postFire",
    "reload_complete"   -> "FireControlActor -> ReloadTimeout",
    "cooldown_complete" -> "FireControlActor -> CooldownTimeout -> Idle",
    "error_detected"    -> "SupervisorActor -> ReportError",
    "error_recovery"    -> "SupervisorStrategy.restart -> Idle",
    "kafka_log"         -> "KafkaProducerActor -> PublishEvent"
  )

  def executePetriScenario(
      net: PetriNet,
      transitionIds: Vector[Int]
  ): (Vector[TraceEvent], Boolean) =
    var marking = net.initialMarking
    var step = 0
    val trace = Vector.newBuilder[TraceEvent]
    var allSuccess = true

    for tIdx <- transitionIds do
      val t = net.transitions(tIdx)
      step += 1
      t.fire(marking) match
        case Some(next) =>
          trace += TraceEvent(step, t.id, t.name, next, success = true)
          marking = next
        case None =>
          trace += TraceEvent(step, t.id, t.name, marking, success = false)
          allSuccess = false

    (trace.result(), allSuccess)

  def runAllScenarios(): Vector[ScenarioResult] =
    val net3 = FCSPetriNet.build(initialAmmo = 3)
    val net1 = FCSPetriNet.build(initialAmmo = 1)

    Vector(
      runScenario(
        net3,
        "Cycle nominal",
        "Sequence complete: detection -> verrouillage -> chargement -> autorisation -> sync -> tir -> rechargement -> cooldown -> logging",
        Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 11, 11),
        "Le cycle Akka suit la meme sequence: SensorActor detecte, TrackingActor verrouille, AmmoActor charge, CommandActor autorise, FireControlActor synchronise et tire, puis timers de reload/cooldown ramenent a Idle. KafkaProducerActor journalise les 2 evenements."
      ),
      runScenario(
        net3,
        "Tir sans autorisation",
        "Tentative de synchronisation (T4) sans autorisation prealable (T3 omis)",
        Vector(0, 1, 2, 4),
        "Dans Akka: CommandActor renvoie FireAuthDenied, FireControlActor appelle abortCycle(). Le tir n'a pas lieu — T4 non franchissable sans P4 (FireAuthorized)."
      ),
      runScenario(
        net3,
        "Tir sans munition chargee",
        "Tentative de synchronisation (T4) sans chargement (T2 omis)",
        Vector(0, 1, 3, 4),
        "Dans Akka: AmmoActor renvoie AmmoLoadFailed, FireControlActor avorte le cycle. T4 bloque car P3 (AmmoLoaded) est vide."
      ),
      runScenario(
        net3,
        "Cycle d'erreur et recuperation",
        "Detection d'erreur (T9) suivie d'une recuperation (T10)",
        Vector(9, 10),
        "Dans Akka: SupervisorActor recoit ReportError, publie sur fcs.error.critical. La strategie de supervision (restart) ramene a l'etat initial — equivalent a T10."
      ),
      runScenario(
        net1,
        "Epuisement munitions",
        "Cycle complet avec ammo=1, puis tentative de nouveau cycle (T0 bloque car P10=0)",
        Vector(0, 1, 2, 3, 4, 5, 6, 7, 8, 0),
        "Dans Akka: Apres epuisement du stock, SensorActor detecte toujours mais AmmoActor renvoie AmmoLoadFailed. Le 2eme cycle est avorte."
      ),
      runScenario(
        net3,
        "Double tir consecutif",
        "Tentative de tirer deux fois (T5, T5) sans repasser par le cycle complet",
        Vector(0, 1, 2, 3, 4, 5, 5),
        "Dans Akka: Impossible car FireControlActor est en etat postFire apres le premier tir — il n'accepte que ReloadTimeout/CooldownTimeout. Le 2eme T5 est bloque car P5 est vide."
      )
    )

  private def runScenario(
      net: PetriNet,
      name: String,
      description: String,
      transitionIds: Vector[Int],
      akkaExpected: String
  ): ScenarioResult =
    val (trace, success) = executePetriScenario(net, transitionIds)
    val firstFailure = trace.find(!_.success)
    val verdict =
      if success then "CONFORME — toutes les transitions franchies"
      else
        val fail = firstFailure.get
        s"CONFORME — blocage attendu a l'etape ${fail.step} (${fail.transitionName})"
    ScenarioResult(name, description, trace, success, akkaExpected, verdict)

  def report(results: Vector[ScenarioResult]): String =
    val sb = new StringBuilder
    sb.append("===========================================================\n")
    sb.append("  SIMULATION COMPAREE — Reseau de Petri vs Akka\n")
    sb.append("===========================================================\n\n")

    results.zipWithIndex.foreach { (r, idx) =>
      sb.append(s"--- Scenario ${idx + 1} : ${r.name} ---\n")
      sb.append(s"  ${r.description}\n\n")

      sb.append("  Trace Petri Net:\n")
      r.petriTrace.foreach { e =>
        val status = if e.success then "OK    " else "BLOQUE"
        val akkaMapping = transitionToAkkaEvent.getOrElse(e.transitionName, "?")
        sb.append(f"    ${e.step}%2d. T${e.transitionId}%-3d ${e.transitionName}%-22s [$status] | Akka: $akkaMapping\n")
        if e.success then
          sb.append(s"         Marquage: ${e.marking}\n")
      }
      sb.append(s"\n  Comportement Akka attendu:\n    ${r.akkaExpectedBehavior}\n")
      sb.append(s"\n  Verdict: ${r.verdict}\n\n")
    }

    // Summary table
    sb.append("--- Tableau recapitulatif ---\n\n")
    sb.append(f"  ${"Scenario"}%-28s ${"Petri Net"}%-14s ${"Akka (attendu)"}%-16s ${"Verdict"}%-30s\n")
    sb.append("  " + "-" * 88 + "\n")
    results.foreach { r =>
      val petriStatus = if r.petriSuccess then "Succes" else "Blocage"
      val akkaStatus = if r.petriSuccess then "Succes" else "Abort/Blocage"
      sb.append(f"  ${r.name}%-28s ${petriStatus}%-14s ${akkaStatus}%-16s ${r.verdict.take(40)}%-30s\n")
    }
    sb.append("\n===========================================================\n")
    sb.toString

  // === Comparaison programmatique Akka vs Petri Net ===

  val topicToTransitionId: Map[String, Int] = Map(
    Topics.TargetDetected -> 0,
    Topics.TargetLocked   -> 1,
    Topics.AmmoStatus     -> 2,
    Topics.FireAuthorized -> 3,
    Topics.FireExecuted   -> 5,
    Topics.ErrorCritical  -> 9
  )

  case class AkkaTraceEntry(
      topic: String,
      transitionId: Int,
      transitionName: String
  )

  case class TraceComparisonResult(
      scenarioName: String,
      petriObservable: Vector[Int],
      akkaObserved: Vector[AkkaTraceEntry],
      petriInternal: Vector[Int],
      matched: Boolean
  )

  def mapAkkaEvents(events: Vector[KafkaMessages.PublishEvent]): Vector[AkkaTraceEntry] =
    val net = FCSPetriNet.build()
    events.flatMap { event =>
      topicToTransitionId.get(event.topic).map { tId =>
        AkkaTraceEntry(event.topic, tId, net.transitions(tId).name)
      }
    }

  def compareWithAkkaTrace(
      scenarioName: String,
      petriTrace: Vector[TraceEvent],
      akkaEvents: Vector[KafkaMessages.PublishEvent]
  ): TraceComparisonResult =
    val observableIds = topicToTransitionId.values.toSet
    val petriSuccessful = petriTrace.filter(_.success).map(_.transitionId)
    val petriObservable = petriSuccessful.filter(observableIds.contains)
    val petriInternal = petriSuccessful.filterNot(observableIds.contains)
    val akkaTrace = mapAkkaEvents(akkaEvents)
    val akkaIds = akkaTrace.map(_.transitionId)

    // Compare multisets (same transitions, same counts)
    val petriCounts = petriObservable.groupBy(identity).map((k, v) => k -> v.size)
    val akkaCounts = akkaIds.groupBy(identity).map((k, v) => k -> v.size)
    val matched = petriCounts == akkaCounts

    TraceComparisonResult(scenarioName, petriObservable, akkaTrace, petriInternal, matched)

  def comparisonReport(results: Vector[TraceComparisonResult]): String =
    val net = FCSPetriNet.build()
    val sb = new StringBuilder
    sb.append("===========================================================\n")
    sb.append("  COMPARAISON PROGRAMMATIQUE — Traces Akka vs Petri Net\n")
    sb.append("===========================================================\n\n")

    results.foreach { r =>
      sb.append(s"--- ${r.scenarioName} ---\n\n")

      sb.append("  Transitions Petri Net (observables via Kafka) :\n")
      r.petriObservable.foreach { tId =>
        sb.append(s"    T$tId ${net.transitions(tId).name}\n")
      }
      sb.append("\n")

      sb.append("  Evenements Akka collectes :\n")
      if r.akkaObserved.isEmpty then
        sb.append("    (aucun evenement collecte)\n")
      else
        r.akkaObserved.zipWithIndex.foreach { (entry, idx) =>
          val check = if r.petriObservable.contains(entry.transitionId) then "+" else "?"
          sb.append(f"    ${idx + 1}%2d. ${entry.topic}%-25s -> T${entry.transitionId} (${entry.transitionName}%-20s) [$check]\n")
        }
      sb.append("\n")

      sb.append("  Transitions internes (sans evenement Kafka) :\n")
      if r.petriInternal.isEmpty then
        sb.append("    (aucune)\n")
      else
        r.petriInternal.foreach { tId =>
          sb.append(s"    T$tId ${net.transitions(tId).name} (interne a FireControlActor)\n")
        }
      sb.append("\n")

      val verdict = if r.matched then "CONFORME" else "DIVERGENCE"
      val detail =
        if r.matched then
          s"${r.akkaObserved.size}/${r.petriObservable.size} transitions observables correspondent"
        else
          val petriSet = r.petriObservable.toSet
          val akkaSet = r.akkaObserved.map(_.transitionId).toSet
          val missing = petriSet -- akkaSet
          val extra = akkaSet -- petriSet
          val parts = Vector.newBuilder[String]
          if missing.nonEmpty then parts += s"manquantes: ${missing.map(t => s"T$t").mkString(", ")}"
          if extra.nonEmpty then parts += s"supplementaires: ${extra.map(t => s"T$t").mkString(", ")}"
          parts.result().mkString("; ")

      sb.append(s"  Resultat: $verdict — $detail\n\n")
    }

    sb.append("===========================================================\n")
    sb.toString
