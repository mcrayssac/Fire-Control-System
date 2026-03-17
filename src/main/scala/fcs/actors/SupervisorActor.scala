package fcs.actors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.model.SupervisorProtocol.*
import fcs.kafka.Topics
import scala.concurrent.duration.*

object SupervisorActor:

  case class ActorRefs(
      sensor: ActorRef[SensorProtocol.Command],
      tracking: ActorRef[TrackingProtocol.Command],
      ammo: ActorRef[AmmoProtocol.Command],
      command: ActorRef[CommandProtocol.Command],
      fireControl: ActorRef[FireControlProtocol.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      kafkaConsumer: ActorRef[KafkaConsumerActor.Command]
  )

  def apply(): Behavior[SupervisorProtocol.Command] =
    Behaviors.setup { context =>
      context.log.info("Fire Control System (FCS) - Demarrage")
      val refs = spawnActors(context)
      context.log.info("Tous les acteurs crees et supervises")
      running(refs)
    }

  private def spawnActors(context: ActorContext[SupervisorProtocol.Command]): ActorRefs =
    val kafkaProducer = context.spawn(
      Behaviors.supervise(KafkaProducerActor())
        .onFailure[Exception](SupervisorStrategy.restart.withLimit(3, 10.seconds)),
      "kafka-producer"
    )
    val kafkaConsumer = context.spawn(
      Behaviors.supervise(KafkaConsumerActor())
        .onFailure[Exception](SupervisorStrategy.restart.withLimit(3, 10.seconds)),
      "kafka-consumer"
    )
    val tracking = context.spawn(
      Behaviors.supervise(TrackingActor(kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "tracking"
    )
    val ammo = context.spawn(
      Behaviors.supervise(AmmoActor(kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "ammo"
    )
    val command = context.spawn(
      Behaviors.supervise(CommandActor(kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "command"
    )
    val fireControl = context.spawn(
      Behaviors.supervise(FireControlActor(tracking, ammo, command, kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "fire-control"
    )
    val sensor = context.spawn(
      Behaviors.supervise(SensorActor(fireControl, kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart.withLimit(5, 30.seconds)),
      "sensor"
    )
    ActorRefs(sensor, tracking, ammo, command, fireControl, kafkaProducer, kafkaConsumer)

  private def running(refs: ActorRefs): Behavior[SupervisorProtocol.Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StartSystem =>
          context.log.info("FCS: Systeme demarre")
          refs.sensor ! SensorProtocol.StartScanning
          refs.kafkaConsumer ! KafkaConsumerActor.StartConsuming
          Behaviors.same

        case StopSystem =>
          context.log.info("FCS: Arret du systeme")
          refs.sensor ! SensorProtocol.StopScanning
          refs.kafkaConsumer ! KafkaConsumerActor.StopConsuming
          Behaviors.stopped

        case ReportError(source, error, timestamp) =>
          context.log.error(s"FCS: Erreur critique depuis $source - ${error.getMessage}")
          refs.kafkaProducer ! KafkaMessages.PublishEvent(
            topic = Topics.ErrorCritical, key = source,
            payload = s"""{"source":"$source","error":"${error.getMessage}","timestamp":"$timestamp"}"""
          )
          Behaviors.same

        case SimulateScenario(scenario) =>
          runScenario(context, refs, scenario)
          Behaviors.same
    }

  private def runScenario(
      context: ActorContext[SupervisorProtocol.Command],
      refs: ActorRefs,
      scenario: SimulationScenario
  ): Unit =
    import SimulationScenario.*
    scenario match
      case NominalFireCycle =>
        context.log.info("Scenario: Cycle de tir nominal")
        refs.sensor ! SensorProtocol.SimulateDetection(
          TargetCoordinates(1500, 200, 0, bearing = 45.0, range = 2000.0)
        )
      case FireWithoutAuthorization =>
        context.log.info("Scenario: Tir sans autorisation (doit être refusé)")
        refs.sensor ! SensorProtocol.SimulateDetection(
          TargetCoordinates(5000, 0, 0, bearing = 180.0, range = 4500.0)
        )
      case AmmoExhausted =>
        context.log.info("Scenario: Stock épuisé")
        (1 to 12).foreach { i =>
          refs.sensor ! SensorProtocol.SimulateDetection(
            TargetCoordinates(1000.0 + i * 100, 0, 0, bearing = 30.0 + i * 5, range = 1500.0)
          )
        }
      case ErrorDuringTracking =>
        context.log.info("Scenario: Erreur pendant le verrouillage")
        context.self ! ReportError("tracking", new RuntimeException("Capteur gyroscopique défaillant"))
      case ConcurrentDetections =>
        context.log.info("Scenario: Détections concurrentes")
        refs.sensor ! SensorProtocol.SimulateDetection(TargetCoordinates(100, 0, 0, 10.0, 1000.0))
        refs.sensor ! SensorProtocol.SimulateDetection(TargetCoordinates(200, 0, 0, 20.0, 1500.0))
      case KafkaOffline =>
        context.log.info("Scenario: Kafka offline")
        refs.sensor ! SensorProtocol.SimulateDetection(
          TargetCoordinates(1000, 0, 0, 45.0, 2000.0)
        )
