package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.kafka.Topics
import java.time.Instant

// =============================================================================
// CommandActor — Autorisation de tir du commandant
// Réseau de Pétri : transition T3 (authorize_fire), P2 → P4
// Valide les règles d'engagement (ROE) avant d'autoriser le tir
// =============================================================================

object CommandActor:

  import fcs.model.CommandActor.*

  def apply(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      roe: ROE = ROE()
  ): Behavior[Command] =
    ready(kafkaProducer, roe)

  /** État prêt — en attente de demande d'autorisation */
  private def ready(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      roe: ROE
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case RequestAuthorization(cycleId, solution, replyTo) =>
          validateROE(context, roe, solution) match
            case Right(_) =>
              context.log.info(
                s"🟢 CommandActor: Tir AUTORISÉ [${cycleId.value.take(8)}] " +
                  s"— ROE validées"
              )

              // T3 franchie
              replyTo ! FireControlActor.FireAuthConfirmed(cycleId)

              kafkaProducer ! KafkaMessages.PublishEvent(
                topic = Topics.FireAuthorized,
                key = cycleId.value,
                payload = s"""{"cycleId":"${cycleId.value}","authorized":true,"timestamp":"${Instant.now()}"}"""
              )

            case Left(reason) =>
              context.log.warn(
                s"🔴 CommandActor: Tir REFUSÉ [${cycleId.value.take(8)}] — $reason"
              )

              replyTo ! FireControlActor.FireAuthDenied(cycleId, reason)

              kafkaProducer ! KafkaMessages.PublishEvent(
                topic = Topics.FireAuthorized,
                key = cycleId.value,
                payload = s"""{"cycleId":"${cycleId.value}","authorized":false,"reason":"$reason"}"""
              )
          Behaviors.same

        case RevokeAuthorization(cycleId) =>
          context.log.warn(
            s"⚠️ CommandActor: Révocation autorisation [${cycleId.value.take(8)}]"
          )
          Behaviors.same
    }

  /** Validation des règles d'engagement */
  private def validateROE(
      context: ActorContext[Command],
      roe: ROE,
      solution: BallisticSolution
  ): Either[String, Unit] =
    if !roe.weaponsFree then
      // En mode non-weaponsFree, on simule l'autorisation du commandant
      // (dans un vrai système, il y aurait une interaction humaine)
      context.log.info("CommandActor: Mode standard — vérification ROE automatique")

    if solution.confidence < roe.minConfidence then
      Left(s"Confiance insuffisante: ${solution.confidence} < ${roe.minConfidence}")
    else
      Right(())
