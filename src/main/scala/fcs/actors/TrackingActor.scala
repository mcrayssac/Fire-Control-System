package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import fcs.model.*
import fcs.model.TrackingProtocol.*
import scala.concurrent.duration.*

// =============================================================================
// TrackingActor — Verrouillage de cible
// Réseau de Pétri : transition T1 (lock_target), P1 → P2
// =============================================================================

object TrackingActor:

  // Commande interne (timer) — définie ici car TrackingProtocol.Command n'est pas sealed
  private case class TrackingTimeout(cycleId: FireCycleId) extends TrackingProtocol.Command
  private val TrackingTimeoutDuration = 5.seconds

  def apply(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[TrackingProtocol.Command] =
    Behaviors.withTimers { timers =>
      idle(kafkaProducer, timers)
    }

  private def idle(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[TrackingProtocol.Command]
  ): Behavior[TrackingProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case TrackTarget(cycleId, coordinates, replyTo) =>
          context.log.info(s"🔒 TrackingActor: Verrouillage cible [${cycleId.value.take(8)}]...")
          val solution = computeBallisticSolution(coordinates)
          if solution.confidence >= 0.5 then
            context.log.info(
              s"✅ TrackingActor: Cible verrouillée [${cycleId.value.take(8)}] confiance=${solution.confidence}"
            )
            replyTo ! FireControlProtocol.TargetLockConfirmed(cycleId, solution)
            kafkaProducer ! KafkaMessages.PublishEvent(
              topic = fcs.kafka.Topics.TargetLocked,
              key = cycleId.value,
              payload = s"""{"cycleId":"${cycleId.value}","elevation":${solution.elevation},"confidence":${solution.confidence}}"""
            )
            timers.startSingleTimer(cycleId.value, TrackingTimeout(cycleId), TrackingTimeoutDuration)
            tracking(kafkaProducer, timers, cycleId, solution)
          else
            context.log.warn(s"❌ TrackingActor: Verrouillage échoué [${cycleId.value.take(8)}]")
            replyTo ! FireControlProtocol.TargetLockFailed(cycleId, "Confiance insuffisante")
            Behaviors.same
        case LoseTrack(_) => Behaviors.same
        case _: TrackingTimeout => Behaviors.same
        case _ => Behaviors.same
    }

  private def tracking(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[TrackingProtocol.Command],
      currentCycleId: FireCycleId,
      solution: BallisticSolution
  ): Behavior[TrackingProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case LoseTrack(cycleId) if cycleId == currentCycleId =>
          context.log.warn(s"⚠️ TrackingActor: Perte de verrouillage [${cycleId.value.take(8)}]")
          timers.cancel(cycleId.value)
          idle(kafkaProducer, timers)
        case TrackingTimeout(cycleId) if cycleId == currentCycleId =>
          context.log.info(s"TrackingActor: Timeout tracking [${cycleId.value.take(8)}]")
          idle(kafkaProducer, timers)
        case TrackTarget(cycleId, coordinates, replyTo) =>
          context.log.info(s"TrackingActor: Nouvelle cible, abandon de [${currentCycleId.value.take(8)}]")
          timers.cancel(currentCycleId.value)
          idle(kafkaProducer, timers)
        case _ => Behaviors.same
    }

  private def computeBallisticSolution(coords: TargetCoordinates): BallisticSolution =
    val range = coords.range
    val gravity = 9.81
    val muzzleVelocity = 1750.0
    val elevation = Math.toDegrees(Math.atan2(gravity * range, muzzleVelocity * muzzleVelocity))
    val timeOfFlight = range / muzzleVelocity
    val confidence = Math.max(0.0, Math.min(1.0, 1.0 - (range / 5000.0)))
    BallisticSolution(elevation, windage = 0.0, timeOfFlight, confidence)
