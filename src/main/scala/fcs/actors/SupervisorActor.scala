package fcs.actors

import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fcs.model.*
import fcs.kafka.Topics
import java.time.Instant
import scala.concurrent.duration.*

// =============================================================================
// SupervisorActor — Superviseur racine du système FCS
// Réseau de Pétri : gère T9 (error_detected) Px → P9 et T10 (error_recovery) P9 → P0
//
// Stratégies de supervision :
// - OneForOneStrategy : SensorActor, KafkaConsumerActor (indépendants)
// - AllForOneStrategy : TrackingActor, AmmoActor, FireControlActor (groupe critique)
// =============================================================================

object SupervisorActor:

  import fcs.model.SupervisorActor.*

  /** Références vers tous les acteurs enfants */
  case class ActorRefs(
      sensor: ActorRef[fcs.model.SensorActor.Command],
      tracking: ActorRef[fcs.model.TrackingActor.Command],
      ammo: ActorRef[fcs.model.AmmoActor.Command],
      command: ActorRef[fcs.model.CommandActor.Command],
      fireControl: ActorRef[FireControlActor.Command],
      kafkaProducer: ActorRef[KafkaMessages.KafkaCommand],
      kafkaConsumer: ActorRef[KafkaConsumerActor.Command]
  )

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.info("═══════════════════════════════════════════════")
      context.log.info("  Fire Control System (FCS) — Démarrage")
      context.log.info("═══════════════════════════════════════════════")

      // Créer les acteurs avec supervision
      val refs = spawnActors(context)

      context.log.info("✅ Tous les acteurs créés et supervisés")
      running(refs)
    }

  /** Crée et supervise tous les acteurs du système */
  private def spawnActors(context: ActorContext[Command]): ActorRefs =
    // KafkaProducer (indépendant, restart simple)
    val kafkaProducer = context.spawn(
      Behaviors.supervise(KafkaProducerActor())
        .onFailure[Exception](SupervisorStrategy.restart.withLimit(3, 10.seconds)),
      "kafka-producer"
    )

    // KafkaConsumer (indépendant, restart simple)
    val kafkaConsumer = context.spawn(
      Behaviors.supervise(KafkaConsumerActor())
        .onFailure[Exception](SupervisorStrategy.restart.withLimit(3, 10.seconds)),
      "kafka-consumer"
    )

    // TrackingActor (groupe critique)
    val tracking = context.spawn(
      Behaviors.supervise(TrackingActor(kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "tracking"
    )

    // AmmoActor (groupe critique)
    val ammo = context.spawn(
      Behaviors.supervise(AmmoActor(kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "ammo"
    )

    // CommandActor
    val command = context.spawn(
      Behaviors.supervise(CommandActor(kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart),
      "command"
    )

    // FireControlActor (superviseur du groupe critique)
    val fireControl = context.spawn(
      Behaviors.supervise(
        FireControlActor(tracking, ammo, command, kafkaProducer)
      ).onFailure[Exception](SupervisorStrategy.restart),
      "fire-control"
    )

    // SensorActor (indépendant)
    val sensor = context.spawn(
      Behaviors.supervise(SensorActor(fireControl, kafkaProducer))
        .onFailure[Exception](SupervisorStrategy.restart.withLimit(5, 30.seconds)),
      "sensor"
    )

    ActorRefs(sensor, tracking, ammo, command, fireControl, kafkaProducer, kafkaConsumer)

  /** Système en fonctionnement nominal */
  private def running(refs: ActorRefs): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case StartSystem =>
          context.log.info("🚀 FCS: Système démarré — prêt au combat")
          refs.sensor ! fcs.model.SensorActor.StartScanning
          refs.kafkaConsumer ! KafkaConsumerActor.StartConsuming
          Behaviors.same

        case StopSystem =>
          context.log.info("🛑 FCS: Arrêt du système")
          refs.sensor ! fcs.model.SensorActor.StopScanning
          refs.kafkaConsumer ! KafkaConsumerActor.StopConsuming
          Behaviors.stopped

        case ReportError(source, error, timestamp) =>
          // T9 (error_detected) : Px → P9
          context.log.error(
            s"🚨 FCS: Erreur critique depuis $source — ${error.getMessage}"
          )
          refs.kafkaProducer ! KafkaMessages.PublishEvent(
            topic = Topics.ErrorCritical,
            key = source,
            payload = s"""{"source":"$source","error":"${error.getMessage}","timestamp":"$timestamp"}"""
          )
          // T10 (error_recovery) : le restart automatique d'Akka ramène à P0
          Behaviors.same

        case SimulateScenario(scenario) =>
          runScenario(context, refs, scenario)
          Behaviors.same
    }

  /** Exécute un scénario de simulation prédéfini */
  private def runScenario(
      context: ActorContext[Command],
      refs: ActorRefs,
      scenario: SimulationScenario
  ): Unit =
    import SimulationScenario.*

    scenario match
      case NominalFireCycle =>
        context.log.info("📋 Scénario: Cycle de tir nominal")
        val coords = TargetCoordinates(
          x = 1500, y = 200, z = 0,
          bearing = 45.0,
          range = 2000.0
        )
        refs.sensor ! fcs.model.SensorActor.SimulateDetection(coords)

      case FireWithoutAuthorization =>
        context.log.info("📋 Scénario: Tir sans autorisation (doit être refusé)")
        val coords = TargetCoordinates(
          x = 5000, y = 0, z = 0,
          bearing = 180.0,
          range = 4500.0 // Au-delà de la portée → confiance trop faible → refus
        )
        refs.sensor ! fcs.model.SensorActor.SimulateDetection(coords)

      case AmmoExhausted =>
        context.log.info("📋 Scénario: Stock épuisé")
        // Simuler plusieurs tirs jusqu'à épuisement
        (1 to 12).foreach { i =>
          val coords = TargetCoordinates(
            x = 1000.0 + i * 100, y = 0, z = 0,
            bearing = 30.0 + i * 5,
            range = 1500.0
          )
          refs.sensor ! fcs.model.SensorActor.SimulateDetection(coords)
        }

      case ErrorDuringTracking =>
        context.log.info("📋 Scénario: Erreur pendant le verrouillage")
        context.self ! ReportError("tracking", new RuntimeException("Capteur gyroscopique défaillant"))

      case ConcurrentDetections =>
        context.log.info("📋 Scénario: Détections concurrentes")
        val coords1 = TargetCoordinates(100, 0, 0, 10.0, 1000.0)
        val coords2 = TargetCoordinates(200, 0, 0, 20.0, 1500.0)
        refs.sensor ! fcs.model.SensorActor.SimulateDetection(coords1)
        refs.sensor ! fcs.model.SensorActor.SimulateDetection(coords2)

      case KafkaOffline =>
        context.log.info("📋 Scénario: Kafka offline (boucle de tir doit continuer)")
        val coords = TargetCoordinates(1000, 0, 0, 45.0, 2000.0)
        refs.sensor ! fcs.model.SensorActor.SimulateDetection(coords)
