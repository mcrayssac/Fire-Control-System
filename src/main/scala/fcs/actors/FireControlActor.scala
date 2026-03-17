package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import fcs.model.*
import fcs.model.FireControlProtocol.*
import fcs.kafka.Topics
import java.time.Instant
import scala.concurrent.duration.*

// =============================================================================
// FireControlActor — Orchestrateur central du cycle de tir
// Réseau de Pétri : T4 (sync), T5 (fire), T6 (end_fire), T7/T8
// =============================================================================

object FireControlActor:

  private val CooldownDuration = 3.seconds
  private val ReloadDuration   = 2.seconds

  def apply(
      trackingActor: ActorRef[TrackingProtocol.Command],
      ammoActor: ActorRef[AmmoProtocol.Command],
      commandActor: ActorRef[CommandProtocol.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[FireControlProtocol.Command] =
    Behaviors.withTimers { timers =>
      idle(trackingActor, ammoActor, commandActor, kafkaProducer, timers)
    }

  // ── P0 : Idle ──────────────────────────────────────────────────────
  private def idle(
      tracking: ActorRef[TrackingProtocol.Command],
      ammo: ActorRef[AmmoProtocol.Command],
      command: ActorRef[CommandProtocol.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[FireControlProtocol.Command]
  ): Behavior[FireControlProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case InitiateFireCycle(coordinates, ammoType) =>
          val cycleId = FireCycleId()
          val state = FireCycleState(
            cycleId = cycleId, phase = FCSPhase.TargetDetected,
            coordinates = Some(coordinates), ammoType = Some(ammoType)
          )
          context.log.info(s"🔄 FireControl: Nouveau cycle [${cycleId.value.take(8)}]")
          tracking ! TrackingProtocol.TrackTarget(cycleId, coordinates, context.self)
          ammo ! AmmoProtocol.LoadAmmo(cycleId, ammoType, context.self)
          awaitingPreconditions(tracking, ammo, command, kafka, timers, state)
        case _ => Behaviors.same
    }

  // ── Attente des 3 préconditions (P2 + P3 + P4 → T4) ──────────────
  private def awaitingPreconditions(
      tracking: ActorRef[TrackingProtocol.Command],
      ammo: ActorRef[AmmoProtocol.Command],
      command: ActorRef[CommandProtocol.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[FireControlProtocol.Command],
      state: FireCycleState
  ): Behavior[FireControlProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case TargetLockConfirmed(cycleId, solution) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Lock confirmé [${cycleId.value.take(8)}]")
          val newState = state.copy(targetLocked = true, solution = Some(solution), phase = FCSPhase.TargetLocked)
          command ! CommandProtocol.RequestAuthorization(cycleId, solution, context.self)
          checkSync(context, tracking, ammo, command, kafka, timers, newState)

        case TargetLockFailed(cycleId, reason) if cycleId == state.cycleId =>
          context.log.error(s"  ✗ Lock échoué [${cycleId.value.take(8)}]: $reason")
          abortCycle(context, kafka, state, reason)
          idle(tracking, ammo, command, kafka, timers)

        case AmmoLoadConfirmed(cycleId, ammoType, remaining) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Munition chargée [${cycleId.value.take(8)}] ($ammoType, reste: $remaining)")
          val newState = state.copy(ammoLoaded = true, phase = FCSPhase.AmmoLoaded)
          checkSync(context, tracking, ammo, command, kafka, timers, newState)

        case AmmoLoadFailed(cycleId) if cycleId == state.cycleId =>
          context.log.error(s"  ✗ Chargement échoué [${cycleId.value.take(8)}]: stock vide")
          abortCycle(context, kafka, state, "Stock de munitions épuisé")
          idle(tracking, ammo, command, kafka, timers)

        case FireAuthConfirmed(cycleId) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Autorisation confirmée [${cycleId.value.take(8)}]")
          val newState = state.copy(fireAuthorized = true, phase = FCSPhase.FireAuthorized)
          checkSync(context, tracking, ammo, command, kafka, timers, newState)

        case FireAuthDenied(cycleId, reason) if cycleId == state.cycleId =>
          context.log.warn(s"  ✗ Autorisation refusée [${cycleId.value.take(8)}]: $reason")
          abortCycle(context, kafka, state, s"Autorisation refusée: $reason")
          idle(tracking, ammo, command, kafka, timers)

        case AbortFireCycle(cycleId, reason) if cycleId == state.cycleId =>
          abortCycle(context, kafka, state, reason)
          idle(tracking, ammo, command, kafka, timers)

        case _ => Behaviors.same
    }

  private def checkSync(
      context: ActorContext[FireControlProtocol.Command],
      tracking: ActorRef[TrackingProtocol.Command],
      ammo: ActorRef[AmmoProtocol.Command],
      command: ActorRef[CommandProtocol.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[FireControlProtocol.Command],
      state: FireCycleState
  ): Behavior[FireControlProtocol.Command] =
    if state.isReadyToFire then
      executeFire(context, tracking, ammo, command, kafka, timers, state)
    else
      awaitingPreconditions(tracking, ammo, command, kafka, timers, state)

  // ── T5 : Fire ──────────────────────────────────────────────────────
  private def executeFire(
      context: ActorContext[FireControlProtocol.Command],
      tracking: ActorRef[TrackingProtocol.Command],
      ammo: ActorRef[AmmoProtocol.Command],
      command: ActorRef[CommandProtocol.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[FireControlProtocol.Command],
      state: FireCycleState
  ): Behavior[FireControlProtocol.Command] =
    val solution = state.solution.get
    val ammoType = state.ammoType.get
    val cycleId = state.cycleId
    context.log.info(
      s"🔥🔥🔥 FireControl: TIR EXÉCUTÉ [${cycleId.value.take(8)}] — $ammoType, élévation=${solution.elevation}°"
    )
    kafka ! KafkaMessages.PublishEvent(
      topic = Topics.FireExecuted, key = cycleId.value,
      payload = s"""{"cycleId":"${cycleId.value}","ammoType":"$ammoType","elevation":${solution.elevation},"timestamp":"${Instant.now()}"}"""
    )
    context.log.info(s"  ↳ Rechargement (${ReloadDuration.toSeconds}s) + cooldown (${CooldownDuration.toSeconds}s)")
    timers.startSingleTimer(s"cooldown-${cycleId.value}", CooldownTimeout(cycleId), CooldownDuration)
    timers.startSingleTimer(s"reload-${cycleId.value}", ReloadTimeout(cycleId), ReloadDuration)
    postFire(tracking, ammo, command, kafka, timers, state.copy(phase = FCSPhase.Firing),
      reloadDone = false, cooldownDone = false)

  // ── Post-tir : T7 (reload) + T8 (cooldown) → P0 ──────────────────
  private def postFire(
      tracking: ActorRef[TrackingProtocol.Command],
      ammo: ActorRef[AmmoProtocol.Command],
      command: ActorRef[CommandProtocol.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[FireControlProtocol.Command],
      state: FireCycleState,
      reloadDone: Boolean,
      cooldownDone: Boolean
  ): Behavior[FireControlProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case ReloadTimeout(cycleId) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Rechargement terminé [${cycleId.value.take(8)}] (T7)")
          if cooldownDone then
            context.log.info(s"✅ FireControl: Cycle complet [${cycleId.value.take(8)}] → Idle")
            idle(tracking, ammo, command, kafka, timers)
          else
            postFire(tracking, ammo, command, kafka, timers, state, reloadDone = true, cooldownDone)

        case CooldownTimeout(cycleId) if cycleId == state.cycleId =>
          context.log.info(s"  ✓ Cooldown terminé [${cycleId.value.take(8)}] (T8)")
          if reloadDone then
            context.log.info(s"✅ FireControl: Cycle complet [${cycleId.value.take(8)}] → Idle")
            idle(tracking, ammo, command, kafka, timers)
          else
            postFire(tracking, ammo, command, kafka, timers, state, reloadDone, cooldownDone = true)

        case _ => Behaviors.same
    }

  private def abortCycle(
      context: ActorContext[FireControlProtocol.Command],
      kafka: ActorRef[KafkaMessages.KafkaCommand],
      state: FireCycleState,
      reason: String
  ): Unit =
    context.log.warn(s"🚫 FireControl: Cycle abandonné [${state.cycleId.value.take(8)}] — $reason")
    kafka ! KafkaMessages.PublishEvent(
      topic = Topics.ErrorCritical, key = state.cycleId.value,
      payload = s"""{"cycleId":"${state.cycleId.value}","reason":"$reason","timestamp":"${Instant.now()}"}"""
    )
