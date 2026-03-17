package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.model.CommandProtocol.*
import fcs.kafka.Topics
import java.time.Instant

// =============================================================================
// CommandActor — Autorisation de tir du commandant
// Réseau de Pétri : transition T3 (authorize_fire), P2 → P4
// =============================================================================

object CommandActor:

  def apply(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      roe: ROE = ROE()
  ): Behavior[CommandProtocol.Command] =
    ready(kafkaProducer, roe)

  private def ready(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      roe: ROE
  ): Behavior[CommandProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case RequestAuthorization(cycleId, solution, replyTo) =>
          validateROE(context, roe, solution) match
            case Right(_) =>
              context.log.info(s"🟢 CommandActor: Tir AUTORISÉ [${cycleId.value.take(8)}]")
              replyTo ! FireControlProtocol.FireAuthConfirmed(cycleId)
              kafkaProducer ! KafkaMessages.PublishEvent(
                topic = Topics.FireAuthorized, key = cycleId.value,
                payload = s"""{"cycleId":"${cycleId.value}","authorized":true,"timestamp":"${Instant.now()}"}"""
              )
            case Left(reason) =>
              context.log.warn(s"🔴 CommandActor: Tir REFUSÉ [${cycleId.value.take(8)}] — $reason")
              replyTo ! FireControlProtocol.FireAuthDenied(cycleId, reason)
          Behaviors.same
        case RevokeAuthorization(cycleId) =>
          context.log.warn(s"⚠️ CommandActor: Révocation [${cycleId.value.take(8)}]")
          Behaviors.same
    }

  private def validateROE(
      context: ActorContext[CommandProtocol.Command],
      roe: ROE,
      solution: BallisticSolution
  ): Either[String, Unit] =
    if solution.confidence < roe.minConfidence then
      Left(s"Confiance insuffisante: ${solution.confidence} < ${roe.minConfidence}")
    else Right(())
