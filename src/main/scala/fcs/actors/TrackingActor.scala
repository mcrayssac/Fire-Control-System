package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import fcs.model.*
import fcs.kafka.Topics
import scala.concurrent.duration.*

// =============================================================================
// TrackingActor — Verrouillage de cible
// Réseau de Pétri : transition T1 (lock_target), P1 → P2
// Calcule la solution de tir balistique, maintient le verrouillage
// =============================================================================

object TrackingActor:

  import fcs.model.TrackingActor.*

  private case class TrackingTimeout(cycleId: FireCycleId) extends Command
  private val TrackingTimeoutDuration = 5.seconds

  def apply(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[Command] =
    Behaviors.withTimers { timers =>
      idle(kafkaProducer, timers)
    }

  /** État idle — pas de cible en cours de tracking */
  private def idle(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case TrackTarget(cycleId, coordinates, replyTo) =>
          context.log.info(
            s"🔒 TrackingActor: Verrouillage cible [${cycleId.value.take(8)}]..."
          )
          // Simuler le calcul balistique
          val solution = computeBallisticSolution(coordinates)

          if solution.confidence >= 0.5 then
            context.log.info(
              s"✅ TrackingActor: Cible verrouillée [${cycleId.value.take(8)}] " +
                s"confiance=${solution.confidence}"
            )

            // T1 franchie : notifie le FireControlActor
            replyTo ! FireControlActor.TargetLockConfirmed(cycleId, solution)

            // Publie sur Kafka
            kafkaProducer ! KafkaMessages.PublishEvent(
              topic = Topics.TargetLocked,
              key = cycleId.value,
              payload = s"""{"cycleId":"${cycleId.value}","elevation":${solution.elevation},"confidence":${solution.confidence}}"""
            )

            // Timer pour perte de verrouillage
            timers.startSingleTimer(
              cycleId.value,
              TrackingTimeout(cycleId),
              TrackingTimeoutDuration
            )
            tracking(kafkaProducer, timers, cycleId, solution)
          else
            context.log.warn(
              s"❌ TrackingActor: Verrouillage échoué [${cycleId.value.take(8)}] " +
                s"confiance=${solution.confidence} trop faible"
            )
            replyTo ! FireControlActor.TargetLockFailed(cycleId, "Confiance insuffisante")
            Behaviors.same

        case LoseTrack(_) =>
          Behaviors.same // Rien à faire en idle

        case _: TrackingTimeout =>
          Behaviors.same
    }

  /** État tracking — cible verrouillée */
  private def tracking(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      timers: TimerScheduler[Command],
      currentCycleId: FireCycleId,
      solution: BallisticSolution
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case LoseTrack(cycleId) if cycleId == currentCycleId =>
          context.log.warn(s"⚠️ TrackingActor: Perte de verrouillage [${cycleId.value.take(8)}]")
          timers.cancel(cycleId.value)
          idle(kafkaProducer, timers)

        case TrackingTimeout(cycleId) if cycleId == currentCycleId =>
          context.log.info(s"TrackingActor: Timeout tracking [${cycleId.value.take(8)}], retour idle")
          idle(kafkaProducer, timers)

        case TrackTarget(cycleId, coordinates, replyTo) =>
          // Nouvelle cible alors qu'on tracke déjà → on switch
          context.log.info(s"TrackingActor: Nouvelle cible, abandon de [${currentCycleId.value.take(8)}]")
          timers.cancel(currentCycleId.value)
          // Rejouer en idle
          idle(kafkaProducer, timers)

        case _ =>
          Behaviors.same
    }

  /** Calcul de la solution balistique (simplifié pour simulation) */
  private def computeBallisticSolution(coords: TargetCoordinates): BallisticSolution =
    val range = coords.range
    // Modèle simplifié : élévation ~ arctan(g*r / v²) + correction
    val gravity = 9.81
    val muzzleVelocity = 1750.0 // m/s pour APFSDS
    val elevation = Math.toDegrees(Math.atan2(gravity * range, muzzleVelocity * muzzleVelocity))
    val timeOfFlight = range / muzzleVelocity
    val windage = 0.0 // Simplifié
    // Confiance décroît avec la distance
    val confidence = Math.max(0.0, Math.min(1.0, 1.0 - (range / 5000.0)))

    BallisticSolution(
      elevation = elevation,
      windage = windage,
      timeOfFlight = timeOfFlight,
      confidence = confidence
    )
