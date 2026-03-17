package fcs.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.KafkaMessages
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

object KafkaProducerActor:

  private val _recordedEvents = new ConcurrentLinkedQueue[KafkaMessages.PublishEvent]()

  def recordedEvents: Vector[KafkaMessages.PublishEvent] =
    _recordedEvents.asScala.toVector

  def clearRecordedEvents(): Unit = _recordedEvents.clear()

  def apply(enableKafka: Boolean = false): Behavior[KafkaMessages.KafkaCommand] =
    Behaviors.setup { context =>
      if enableKafka then
        context.log.info("KafkaProducer: Mode KAFKA active")
      else
        context.log.info("KafkaProducer: Mode SIMULATION (log only)")
      ready(context)
    }

  private def ready(
      context: ActorContext[KafkaMessages.KafkaCommand]
  ): Behavior[KafkaMessages.KafkaCommand] =
    Behaviors.receiveMessage {
      case event @ KafkaMessages.PublishEvent(topic, key, payload, _) =>
        _recordedEvents.add(event)
        context.log.info(s"  [Kafka:$topic] key=${key.take(8)} | ${payload.take(80)}...")
        Behaviors.same
    }
