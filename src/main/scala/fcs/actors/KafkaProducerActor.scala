package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.kafka.{KafkaConfig, Topics}
import java.time.Instant

// =============================================================================
// KafkaProducerActor — Publication des événements sur Kafka
// Réseau de Pétri : alimente P11 (Kafka_Queue)
// Chaque événement critique est publié de manière asynchrone
// sans bloquer la boucle de tir principale
// =============================================================================

object KafkaProducerActor:

  /** Mode de fonctionnement : réel (Kafka) ou simulation (log only) */
  private var kafkaEnabled: Boolean = false

  def apply(enableKafka: Boolean = false): Behavior[KafkaMessages.KafkaCommand] =
    Behaviors.setup { context =>
      kafkaEnabled = enableKafka
      if enableKafka then
        context.log.info("📡 KafkaProducer: Mode KAFKA activé")
      else
        context.log.info("📡 KafkaProducer: Mode SIMULATION (log only)")
      ready(context)
    }

  private def ready(context: ActorContext[KafkaMessages.KafkaCommand]): Behavior[KafkaMessages.KafkaCommand] =
    Behaviors.receiveMessage {
      case KafkaMessages.PublishEvent(topic, key, payload, timestamp) =>
        if kafkaEnabled then
          // TODO: Intégration Alpakka Kafka réelle
          // Pour l'instant, on simule la publication
          publishToKafka(context, topic, key, payload)
        else
          // Mode simulation : log l'événement
          context.log.info(
            s"  📨 [Kafka:$topic] key=$key | ${payload.take(80)}..."
          )
        Behaviors.same
    }

  /** Publication réelle sur Kafka via Alpakka (à implémenter avec l'infra) */
  private def publishToKafka(
      context: ActorContext[KafkaMessages.KafkaCommand],
      topic: String,
      key: String,
      payload: String
  ): Unit =
    // L'intégration réelle utilisera akka-stream-kafka (Alpakka) :
    //
    // val producerSettings = KafkaConfig.producerSettings(context.system)
    // val record = new ProducerRecord[String, String](topic, key, payload)
    // Source.single(record)
    //   .runWith(Producer.plainSink(producerSettings))
    //
    context.log.info(s"  📨 [Kafka:$topic] key=$key | ${payload.take(80)}...")
