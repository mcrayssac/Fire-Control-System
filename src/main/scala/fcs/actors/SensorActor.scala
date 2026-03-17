package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.kafka.Topics
import java.time.Instant

// =============================================================================
// SensorActor — Détection de cibles
// Réseau de Pétri : transition T0 (detect_target), P0 → P1 + P11
// Reçoit les données radar/LIDAR, détecte les cibles,
// publie sur Kafka et notifie le TrackingActor
// =============================================================================

object SensorActor:

  import fcs.model.SensorActor.*

  def apply(
      fireControl: ActorRef[FireControlActor.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[Command] =
    idle(fireControl, kafkaProducer)

  /** État inactif — en attente de commande de scan */
  private def idle(
      fireControl: ActorRef[FireControlActor.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StartScanning =>
          context.log.info("🔍 SensorActor: Démarrage du scan radar/LIDAR")
          scanning(fireControl, kafkaProducer)

        case SimulateDetection(coords) =>
          processDetection(context, fireControl, kafkaProducer, coords)
          Behaviors.same

        case StopScanning =>
          context.log.info("SensorActor: Déjà en mode idle")
          Behaviors.same
    }

  /** État actif — scan en cours */
  private def scanning(
      fireControl: ActorRef[FireControlActor.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StopScanning =>
          context.log.info("SensorActor: Arrêt du scan")
          idle(fireControl, kafkaProducer)

        case SimulateDetection(coords) =>
          processDetection(context, fireControl, kafkaProducer, coords)
          Behaviors.same

        case StartScanning =>
          context.log.info("SensorActor: Scan déjà actif")
          Behaviors.same
    }

  /** Traitement d'une détection de cible (T0) */
  private def processDetection(
      context: ActorContext[Command],
      fireControl: ActorRef[FireControlActor.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      coords: TargetCoordinates
  ): Unit =
    val cycleId = FireCycleId()
    val event = TargetDetected(cycleId, coords)

    context.log.info(
      s"🎯 SensorActor: Cible détectée [${cycleId.value.take(8)}] " +
        s"à bearing=${coords.bearing}°, range=${coords.range}m"
    )

    // Notifie le FireControlActor pour démarrer le cycle de tir
    fireControl ! FireControlActor.InitiateFireCycle(coords)

    // Publie sur Kafka (P11 : Kafka_Queue)
    kafkaProducer ! KafkaMessages.PublishEvent(
      topic = Topics.TargetDetected,
      key = cycleId.value,
      payload = s"""{"cycleId":"${cycleId.value}","bearing":${coords.bearing},"range":${coords.range},"timestamp":"${event.timestamp}"}"""
    )
