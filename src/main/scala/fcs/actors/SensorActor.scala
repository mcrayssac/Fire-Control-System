package fcs.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.model.SensorProtocol.*
import fcs.kafka.Topics
import java.time.Instant

object SensorActor:

  def apply(
      fireControl: ActorRef[FireControlProtocol.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[SensorProtocol.Command] =
    idle(fireControl, kafkaProducer)

  private def idle(
      fireControl: ActorRef[FireControlProtocol.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[SensorProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StartScanning =>
          context.log.info("SensorActor: Demarrage du scan radar/LIDAR")
          scanning(fireControl, kafkaProducer)
        case SimulateDetection(coords) =>
          processDetection(context, fireControl, kafkaProducer, coords)
          Behaviors.same
        case StopScanning =>
          Behaviors.same
    }

  private def scanning(
      fireControl: ActorRef[FireControlProtocol.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand]
  ): Behavior[SensorProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StopScanning =>
          context.log.info("SensorActor: Arrêt du scan")
          idle(fireControl, kafkaProducer)
        case SimulateDetection(coords) =>
          processDetection(context, fireControl, kafkaProducer, coords)
          Behaviors.same
        case StartScanning =>
          Behaviors.same
    }

  private def processDetection(
      context: ActorContext[SensorProtocol.Command],
      fireControl: ActorRef[FireControlProtocol.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      coords: TargetCoordinates
  ): Unit =
    val cycleId = FireCycleId()
    context.log.info(
      s"SensorActor: Cible detectee [${cycleId.value.take(8)}] " +
        s"bearing=${coords.bearing}, range=${coords.range}m"
    )
    fireControl ! FireControlProtocol.InitiateFireCycle(coords)
    kafkaProducer ! KafkaMessages.PublishEvent(
      topic = Topics.TargetDetected,
      key = cycleId.value,
      payload = s"""{"cycleId":"${cycleId.value}","bearing":${coords.bearing},"range":${coords.range},"timestamp":"${Instant.now()}"}"""
    )
