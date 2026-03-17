package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.kafka.Topics
import java.time.Instant

// =============================================================================
// KafkaConsumerActor — Consommation des messages Kafka et audit trail
// Réseau de Pétri : transition T11 (kafka_log), P11 → P12 (Log_Recorded)
// Consomme les messages des topics Kafka pour le logging,
// l'audit trail et le dashboard de monitoring
// =============================================================================

object KafkaConsumerActor:

  sealed trait Command extends FCSMessage
  case class ConsumedMessage(event: KafkaMessages.ConsumedEvent) extends Command
  case object StartConsuming extends Command
  case object StopConsuming extends Command

  /** Journal d'audit en mémoire */
  case class AuditEntry(
      topic: String,
      key: String,
      payload: String,
      offset: Long,
      timestamp: Instant,
      consumedAt: Instant = Instant.now()
  )

  def apply(): Behavior[Command] =
    idle(Vector.empty)

  /** État idle — pas de consommation active */
  private def idle(auditLog: Vector[AuditEntry]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StartConsuming =>
          context.log.info(
            "📥 KafkaConsumer: Démarrage consommation — " +
              s"topics: ${Topics.All.mkString(", ")}"
          )
          // TODO: Démarrer le consumer Alpakka Kafka réel
          // val consumerSettings = KafkaConfig.consumerSettings(context.system)
          // Consumer.plainSource(consumerSettings, Subscriptions.topics(Topics.All: _*))
          //   .map(record => ConsumedMessage(KafkaMessages.ConsumedEvent(...)))
          //   .runWith(Sink.foreach(msg => context.self ! msg))
          consuming(auditLog)

        case ConsumedMessage(event) =>
          val entry = processEvent(context, event)
          idle(auditLog :+ entry)

        case StopConsuming =>
          Behaviors.same
    }

  /** État consommation active */
  private def consuming(auditLog: Vector[AuditEntry]): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case ConsumedMessage(event) =>
          val entry = processEvent(context, event)
          consuming(auditLog :+ entry)

        case StopConsuming =>
          context.log.info(
            s"📥 KafkaConsumer: Arrêt consommation — ${auditLog.size} entrées enregistrées"
          )
          idle(auditLog)

        case StartConsuming =>
          Behaviors.same
    }

  /** Traite un événement consommé (T11 : P11 → P12) */
  private def processEvent(
      context: ActorContext[Command],
      event: KafkaMessages.ConsumedEvent
  ): AuditEntry =
    val entry = AuditEntry(
      topic = event.topic,
      key = event.key,
      payload = event.payload,
      offset = event.offset,
      timestamp = event.timestamp
    )
    context.log.info(
      s"  📝 [Audit] ${event.topic} | offset=${event.offset} | key=${event.key.take(8)}"
    )
    entry
