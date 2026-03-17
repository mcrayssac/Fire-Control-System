package fcs.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.KafkaMessages

object KafkaProducerActor:

  def apply(enableKafka: Boolean = false): Behavior[KafkaMessages.KafkaCommand] =
    Behaviors.setup { context =>
      if enableKafka then
        context.log.info("KafkaProducer: Mode KAFKA active")
      else
        context.log.info("KafkaProducer: Mode SIMULATION (log only)")
      ready(context, enableKafka)
    }

  private def ready(
      context: ActorContext[KafkaMessages.KafkaCommand],
      enableKafka: Boolean
  ): Behavior[KafkaMessages.KafkaCommand] =
    Behaviors.receiveMessage {
      case KafkaMessages.PublishEvent(topic, key, payload, _) =>
        context.log.info(s"  [Kafka:$topic] key=${key.take(8)} | ${payload.take(80)}...")
        Behaviors.same
    }
