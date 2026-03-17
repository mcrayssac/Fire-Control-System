package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.kafka.Topics
import java.time.Instant

// =============================================================================
// AmmoActor — Gestion des munitions
// Réseau de Pétri : transition T2 (load_ammo), P10 → P3
// Gère le stock de munitions et le mécanisme de chargement automatique
// P10 (Ammo_Stock) contient N jetons ; chaque T2 en consomme 1
// =============================================================================

object AmmoActor:

  import fcs.model.AmmoActor.*

  def apply(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      initialStock: AmmoStock = AmmoStock()
  ): Behavior[Command] =
    ready(kafkaProducer, initialStock)

  /** État prêt — en attente de commande de chargement */
  private def ready(
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      stock: AmmoStock
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case LoadAmmo(cycleId, ammoType, replyTo) =>
          stock.consume(ammoType) match
            case Some(newStock) =>
              val remaining = newStock.available(ammoType)
              context.log.info(
                s"📦 AmmoActor: Chargement ${ammoType} [${cycleId.value.take(8)}] " +
                  s"— stock restant: $remaining"
              )

              // T2 franchie : notifie le FireControlActor
              replyTo ! FireControlActor.AmmoLoadConfirmed(
                cycleId, ammoType, remaining
              )

              // Publie le statut sur Kafka
              kafkaProducer ! KafkaMessages.PublishEvent(
                topic = Topics.AmmoStatus,
                key = cycleId.value,
                payload = s"""{"cycleId":"${cycleId.value}","ammoType":"$ammoType","remaining":$remaining,"total":${newStock.total}}"""
              )

              // Alerte si stock bas
              if remaining <= 2 then
                context.log.warn(
                  s"⚠️ AmmoActor: Stock bas pour $ammoType — $remaining restant(s)"
                )

              ready(kafkaProducer, newStock)

            case None =>
              context.log.error(
                s"🚫 AmmoActor: Stock épuisé pour $ammoType [${cycleId.value.take(8)}]"
              )
              replyTo ! FireControlActor.AmmoLoadFailed(cycleId)

              kafkaProducer ! KafkaMessages.PublishEvent(
                topic = Topics.AmmoStatus,
                key = cycleId.value,
                payload = s"""{"cycleId":"${cycleId.value}","ammoType":"$ammoType","status":"EMPTY"}"""
              )
              Behaviors.same

        case ConsumeAmmo(cycleId) =>
          // Confirmation que la munition a été consommée (post-tir)
          context.log.info(s"AmmoActor: Munition consommée [${cycleId.value.take(8)}]")
          Behaviors.same

        case QueryStock =>
          context.log.info(s"📊 AmmoActor: Stock actuel — ${stock.stock}")
          kafkaProducer ! KafkaMessages.PublishEvent(
            topic = Topics.AmmoStatus,
            key = "query",
            payload = s"""{"stock":${stock.stock.map((k, v) => s""""$k":$v""").mkString("{", ",", "}")},"total":${stock.total}}"""
          )
          Behaviors.same
    }
