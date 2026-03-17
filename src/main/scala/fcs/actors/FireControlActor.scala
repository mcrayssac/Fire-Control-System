package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import fcs.model.*
import fcs.kafka.Topics
import java.time.Instant
import scala.concurrent.duration.*

// =============================================================================
// FireControlActor — Orchestrateur central du cycle de tir
// Réseau de Pétri :
//   T4 (ready_sync)  : P2 + P3 + P4 → P5  (synchronisation triple)
//   T5 (fire)        : P5 → P6 + P11       (tir + publication Kafka)
//   T6 (end_fire)    : P6 → P7 + P8        (rechargement + cooldown parallèle)
//   T7 (reload)      : P7 → P0
//   T8 (cooldown)    : P8 → P0
// C'est le superviseur Akka principal du groupe critique
// =============================================================================

object FireControlActor:

  import fcs.model.FireControlActor.*

  private val CooldownDuration = 3.seconds
  private val ReloadDuration   = 2.seconds

  def apply(
      trackingActor: ActorRef[fcs.model.TrackingActor.Command],
      ammoActor: ActorRef[fcs.model.AmmoActor.Command],
      commandActor: ActorRef[fcs.model.CommandActor.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      idle(trackingActor, ammoActor, commandActor, kafkaProducer, timers)
    }

  // ===========================================================================
  // P0 : Idle — en attente d'un nouveau cycle de tir
  // ===========================================================================
  private def idle(
      tracking: ActorRef[fcs.model.TrackingActor.Command],
      ammo: ActorRef[fcs.model.AmmoActor.Command],
      command: ActorRef[fcs.model.CommandActor.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case InitiateFireCycle(coordinates, ammoType) =>
          val cycleId = FireCycleId()
          val state = FireCycleState(
            cycleId = cycleId,
            phase = FCSPhase.TargetDetected,
            coordinates = Some(coordinates),
            ammoType = Some(ammoType)
          )
          context.log.info(
            s"🔄 FireControl: Nouveau cycle de tir [${cycleId.value.take(8)}]"
          )

          // Lancer les 3 branches en parallèle :
          // 1. Verrouillage cible (→ P2)
          tracking ! fcs.model.TrackingActor.TrackTarget(cycleId, coordinates, context.self)
          // 2. Chargement munition (→ P3)
          ammo ! fcs.model.AmmoActor.LoadAmmo(cycleId, ammoType, context.self)

          // L'autorisation (→ P4) sera demandée après le lock (on a besoin de la solution)
          awaitingPreconditions(tracking, ammo, command, kafka, timers, state)

        case _ =>
          Behaviors.same
    }

  // ===========================================================================
  // En attente des 3 préconditions (P2 + P3 + P4)
  // Correspond à la convergence vers T4 (ready_sync)
  // ===========================================================================
  private def awaitingPreconditions(
      tracking: ActorRef[fcs.model.TrackingActor.Command],
      ammo: ActorRef[fcs.model.AmmoActor.Command],
      command: ActorRef[fcs.model.CommandActor.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command],
      state: FireCycleState
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        // ── Réponse du TrackingActor : cible verrouillée (P2 marqué) ──
        case TargetLockConfirmed(cycleId, solution) if cycleId == state.cycleId =>
          context.log.info(
            s"  ✓ Lock confirmé [${cycleId.value.take(8)}]"
          )
          val newState = state.copy(
            targetLocked = true,
            solution = Some(solution),
            phase = FCSPhase.TargetLocked
          )
          // Maintenant on peut demander l'autorisation avec la solution
          command ! fcs.model.CommandActor.RequestAuthorization(
            cycleId, solution, context.self
          )
          checkSync(context, tracking, ammo, command, kafka, timers, newState)

        case TargetLockFailed(cycleId, reason) if cycleId == state.cycleId =>
          context.log.error(s"  ✗ Lock échoué [${cycleId.value.take(8)}]: $reason")
          abortCycle(context, kafka, state, reason)
          idle(tracking, ammo, command, kafka, timers)

        // ── Réponse du AmmoActor : munition chargée (P3 marqué) ──
        case AmmoLoadConfirmed(cycleId, ammoType, remaining) if cycleId == state.cycleId =>
          context.log.info(
            s"  ✓ Munition chargée [${cycleId.value.take(8)}] ($ammoType, reste: $remaining)"
          )
          val newState = state.copy(ammoLoaded = true, phase = FCSPhase.AmmoLoaded)
          checkSync(context, tracking, ammo, command, kafka, timers, newState)

        case AmmoLoadFailed(cycleId) if cycleId == state.cycleId =>
          context.log.error(s"  ✗ Chargement échoué [${cycleId.value.take(8)}]: stock vide")
          abortCycle(context, kafka, state, "Stock de munitions épuisé")
          idle(tracking, ammo, command, kafka, timers)

        // ── Réponse du CommandActor : tir autorisé (P4 marqué) ──
        case FireAuthConfirmed(cycleId) if cycleId == state.cycleId =>
          context.log.info(
            s"  ✓ Autorisation confirmée [${cycleId.value.take(8)}]"
          )
          val newState = state.copy(fireAuthorized = true, phase = FCSPhase.FireAuthorized)
          checkSync(context, tracking, ammo, command, kafka, timers, newState)

        case FireAuthDenied(cycleId, reason) if cycleId == state.cycleId =>
          context.log.warn(s"  ✗ Autorisation refusée [${cycleId.value.take(8)}]: $reason")
          abortCycle(context, kafka, state, s"Autorisation refusée: $reason")
          idle(tracking, ammo, command, kafka, timers)

        // ── Abort explicite ──
        case AbortFireCycle(cycleId, reason) if cycleId == state.cycleId =>
          abortCycle(context, kafka, state, reason)
          idle(tracking, ammo, command, kafka, timers)

        case _ =>
          Behaviors.same
    }

  /** Vérifie si les 3 préconditions sont réunies (T4 sync) */
  private def checkSync(
      context: ActorContext[Command],
      tracking: ActorRef[fcs.model.TrackingActor.Command],
      ammo: ActorRef[fcs.model.AmmoActor.Command],
      command: ActorRef[fcs.model.CommandActor.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command],
      state: FireCycleState
  ): Behavior[Command] =
    if state.isReadyToFire then
      // ═══════════════════════════════════════════════════════════════════
      // T4 (ready_sync) FRANCHIE → P5 (Ready_To_Fire)
      // Puis immédiatement T5 (fire) → P6 (Firing)
      // ═══════════════════════════════════════════════════════════════════
      executeFire(context, tracking, ammo, command, kafka, timers, state)
    else
      awaitingPreconditions(tracking, ammo, command, kafka, timers, state)

  // ===========================================================================
  // T5 : Fire — Exécution du tir
  // P5 → P6 + P11
  // ===========================================================================
  private def executeFire(
      context: ActorContext[Command],
      tracking: ActorRef[fcs.model.TrackingActor.Command],
      ammo: ActorRef[fcs.model.AmmoActor.Command],
      command: ActorRef[fcs.model.CommandActor.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command],
      state: FireCycleState
  ): Behavior[Command] =
    val solution = state.solution.get
    val ammoType = state.ammoType.get
    val cycleId = state.cycleId

    context.log.info(
      s"🔥🔥🔥 FireControl: TIR EXÉCUTÉ [${cycleId.value.take(8)}] " +
        s"— $ammoType, élévation=${solution.elevation}°"
    )

    // Publie sur Kafka (P11)
    kafka ! KafkaMessages.PublishEvent(
      topic = Topics.FireExecuted,
      key = cycleId.value,
      payload = s"""{"cycleId":"${cycleId.value}","ammoType":"$ammoType","elevation":${solution.elevation},"confidence":${solution.confidence},"timestamp":"${Instant.now()}"}"""
    )

    // T6 (end_fire) : P6 → P7 + P8 (rechargement + cooldown parallèle)
    context.log.info(
      s"  ↳ Lancement rechargement (${ReloadDuration.toSeconds}s) + cooldown (${CooldownDuration.toSeconds}s)"
    )

    timers.startSingleTimer(
      s"cooldown-${cycleId.value}",
      CooldownTimeout(cycleId),
      CooldownDuration
    )
    timers.startSingleTimer(
      s"reload-${cycleId.value}",
      ReloadTimeout(cycleId),
      ReloadDuration
    )

    val firingState = state.copy(phase = FCSPhase.Firing)
    postFire(tracking, ammo, command, kafka, timers, firingState,
      reloadComplete = false, cooldownComplete = false)

  // ===========================================================================
  // Post-tir : attente rechargement (T7: P7→P0) + cooldown (T8: P8→P0)
  // ===========================================================================
  private def postFire(
      tracking: ActorRef[fcs.model.TrackingActor.Command],
      ammo: ActorRef[fcs.model.AmmoActor.Command],
      command: ActorRef[fcs.model.CommandActor.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command],
      state: FireCycleState,
      reloadComplete: Boolean,
      cooldownComplete: Boolean
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case ReloadTimeout(cycleId) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Rechargement terminé [${cycleId.value.take(8)}] (T7)")
          if cooldownComplete then
            // Les deux sont terminés → retour P0 (Idle)
            context.log.info(s"✅ FireControl: Cycle complet [${cycleId.value.take(8)}] → Idle")
            idle(tracking, ammo, command, kafka, timers)
          else
            postFire(tracking, ammo, command, kafka, timers, state,
              reloadComplete = true, cooldownComplete = cooldownComplete)

        case CooldownTimeout(cycleId) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Cooldown terminé [${cycleId.value.take(8)}] (T8)")
          if reloadComplete then
            context.log.info(s"✅ FireControl: Cycle complet [${cycleId.value.take(8)}] → Idle")
            idle(tracking, ammo, command, kafka, timers)
          else
            postFire(tracking, ammo, command, kafka, timers, state,
              reloadComplete = reloadComplete, cooldownComplete = true)

        case _ =>
          // Ignore les autres messages pendant le post-tir
          Behaviors.same
    }

  /** Abandonne le cycle de tir et publie l'erreur */
  private def abortCycle(
      context: ActorContext[Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      state: FireCycleState,
      reason: String
  ): Unit =
    context.log.warn(
      s"🚫 FireControl: Cycle abandonné [${state.cycleId.value.take(8)}] — $reason"
    )
    kafka ! KafkaMessages.PublishEvent(
      topic = Topics.ErrorCritical,
      key = state.cycleId.value,
      payload = s"""{"cycleId":"${state.cycleId.value}","reason":"$reason","timestamp":"${Instant.now()}"}"""
    )
