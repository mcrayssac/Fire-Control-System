package fcs.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.KafkaMessages
import fcs.kafka.Topics
import java.time.Instant

// =============================================================================
// KafkaConsumerActor — Consommation des messages Kafka et audit trail
// Réseau de Pétri : transition T11 (kafka_log), P11 → P12 (Log_Recorded)
// =============================================================================

object KafkaConsumerActor:

  // Protocole propre au consumer (pas dans Messages.scala car indépendant)
  sealed trait Command
  case class ConsumedMessage(event: KafkaMessages.ConsumedEvent) extends Command
  case object StartConsuming extends Command
  case object StopConsuming extends Command

  case class AuditEntry(
      topic: String, key: String, payload: String,
      offset: Long, timestamp: Instant, consumedAt: Instant = Instant.now()
  )

  def apply(): Behavior[Command] =
    idle(Vector.empty)

  private def idle(auditLog: Vector[AuditEntry]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StartConsuming =>
          context.log.info(s"📥 KafkaConsumer: Démarrage — topics: ${Topics.All.mkString(", ")}")
          consuming(auditLog)
        case ConsumedMessage(event) =>
          idle(auditLog :+ processEvent(context, event))
        case StopConsuming =>
          Behaviors.same
    }

  private def consuming(auditLog: Vector[AuditEntry]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case ConsumedMessage(event) =>
          consuming(auditLog :+ processEvent(context, event))
        case StopConsuming =>
          context.log.info(s"📥 KafkaConsumer: Arrêt — ${auditLog.size} entrées enregistrées")
          idle(auditLog)
        case StartConsuming => Behaviors.same
    }

  private def processEvent(context: ActorContext[Command], event: KafkaMessages.ConsumedEvent): AuditEntry =
    context.log.info(s"  📝 [Audit] ${event.topic} | offset=${event.offset} | key=${event.key.take(8)}")
    AuditEntry(event.topic, event.key, event.payload, event.offset, event.timestamp)
