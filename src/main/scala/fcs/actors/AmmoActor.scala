package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.model.AmmoProtocol.*
import fcs.kafka.Topics

object AmmoActor:

  def apply(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      initialStock: AmmoStock = AmmoStock()
  ): Behavior[AmmoProtocol.Command] =
    ready(kafkaProducer, initialStock)

  private def ready(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      stock: AmmoStock
  ): Behavior[AmmoProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case LoadAmmo(cycleId, ammoType, replyTo) =>
          stock.consume(ammoType) match
            case Some(newStock) =>
              val remaining = newStock.available(ammoType)
              context.log.info(
                s"AmmoActor: Chargement ${ammoType} [${cycleId.value.take(8)}] - stock restant: $remaining"
              )
              replyTo ! FireControlProtocol.AmmoLoadConfirmed(cycleId, ammoType, remaining)
              kafkaProducer ! KafkaMessages.PublishEvent(
                topic = Topics.AmmoStatus, key = cycleId.value,
                payload = s"""{"cycleId":"${cycleId.value}","ammoType":"$ammoType","remaining":$remaining}"""
              )
              if remaining <= 2 then
                context.log.warn(s"AmmoActor: Stock bas pour $ammoType - $remaining restant(s)")
              ready(kafkaProducer, newStock)
            case None =>
              context.log.error(s"AmmoActor: Stock epuise pour $ammoType [${cycleId.value.take(8)}]")
              replyTo ! FireControlProtocol.AmmoLoadFailed(cycleId)
              Behaviors.same
        case ConsumeAmmo(cycleId) =>
          context.log.info(s"AmmoActor: Munition consommée [${cycleId.value.take(8)}]")
          Behaviors.same
        case QueryStock =>
          context.log.info(s"AmmoActor: Stock actuel - ${stock.stock}")
          Behaviors.same
    }
