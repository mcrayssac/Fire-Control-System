package fcs.model

import akka.actor.typed.ActorRef
import java.time.Instant
import java.util.UUID

// =============================================================================
// Messages du protocole FCS
// Définit tous les messages échangés entre acteurs du système
// =============================================================================

/** Trait racine pour tous les messages du système FCS */
sealed trait FCSMessage extends Serializable

/** Identifiant unique pour un cycle de tir */
case class FireCycleId(value: String = UUID.randomUUID().toString)

/** Coordonnées d'une cible détectée */
case class TargetCoordinates(
    x: Double,
    y: Double,
    z: Double,
    bearing: Double,  // azimut en degrés
    range: Double     // distance en mètres
)

/** Solution de tir balistique calculée */
case class BallisticSolution(
    elevation: Double,    // angle d'élévation
    windage: Double,      // correction vent
    timeOfFlight: Double, // temps de vol en secondes
    confidence: Double    // niveau de confiance [0,1]
)

/** Type de munition */
enum AmmoType:
  case APFSDS  // Armor-Piercing Fin-Stabilized Discarding Sabot
  case HEAT    // High-Explosive Anti-Tank
  case HESH    // High-Explosive Squash Head
  case HE      // High-Explosive

// =============================================================================
// Messages du SensorActor
// =============================================================================
object SensorActor:
  sealed trait Command extends FCSMessage
  case object StartScanning extends Command
  case object StopScanning extends Command
  case class SimulateDetection(coords: TargetCoordinates) extends Command

  sealed trait Event extends FCSMessage
  case class TargetDetected(
      cycleId: FireCycleId,
      coordinates: TargetCoordinates,
      timestamp: Instant = Instant.now()
  ) extends Event

// =============================================================================
// Messages du TrackingActor
// =============================================================================
object TrackingActor:
  sealed trait Command extends FCSMessage
  case class TrackTarget(
      cycleId: FireCycleId,
      coordinates: TargetCoordinates,
      replyTo: ActorRef[FireControlActor.Command]
  ) extends Command
  case class LoseTrack(cycleId: FireCycleId) extends Command

  sealed trait Event extends FCSMessage
  case class TargetLocked(
      cycleId: FireCycleId,
      solution: BallisticSolution,
      timestamp: Instant = Instant.now()
  ) extends Event
  case class TrackingFailed(
      cycleId: FireCycleId,
      reason: String,
      timestamp: Instant = Instant.now()
  ) extends Event

// =============================================================================
// Messages du AmmoActor
// =============================================================================
object AmmoActor:
  sealed trait Command extends FCSMessage
  case class LoadAmmo(
      cycleId: FireCycleId,
      ammoType: AmmoType,
      replyTo: ActorRef[FireControlActor.Command]
  ) extends Command
  case class ConsumeAmmo(cycleId: FireCycleId) extends Command
  case object QueryStock extends Command

  sealed trait Event extends FCSMessage
  case class AmmoLoaded(
      cycleId: FireCycleId,
      ammoType: AmmoType,
      remainingStock: Int,
      timestamp: Instant = Instant.now()
  ) extends Event
  case class AmmoEmpty(
      cycleId: FireCycleId,
      timestamp: Instant = Instant.now()
  ) extends Event
  case class StockStatus(
      stock: Map[AmmoType, Int],
      timestamp: Instant = Instant.now()
  ) extends Event

// =============================================================================
// Messages du CommandActor
// =============================================================================
object CommandActor:
  sealed trait Command extends FCSMessage
  case class RequestAuthorization(
      cycleId: FireCycleId,
      solution: BallisticSolution,
      replyTo: ActorRef[FireControlActor.Command]
  ) extends Command
  case class RevokeAuthorization(cycleId: FireCycleId) extends Command

  /** Règles d'engagement */
  case class ROE(
      weaponsFree: Boolean = false,   // tir libre
      maxRange: Double = 4000.0,      // portée max en mètres
      minConfidence: Double = 0.8     // confiance min pour la solution de tir
  )

  sealed trait Event extends FCSMessage
  case class FireAuthorized(
      cycleId: FireCycleId,
      roe: ROE,
      timestamp: Instant = Instant.now()
  ) extends Event
  case class FireDenied(
      cycleId: FireCycleId,
      reason: String,
      timestamp: Instant = Instant.now()
  ) extends Event

// =============================================================================
// Messages du FireControlActor (Orchestrateur central)
// =============================================================================
object FireControlActor:
  sealed trait Command extends FCSMessage

  // Réponses des sous-acteurs
  case class TargetLockConfirmed(
      cycleId: FireCycleId,
      solution: BallisticSolution
  ) extends Command
  case class TargetLockFailed(
      cycleId: FireCycleId,
      reason: String
  ) extends Command
  case class AmmoLoadConfirmed(
      cycleId: FireCycleId,
      ammoType: AmmoType,
      remainingStock: Int
  ) extends Command
  case class AmmoLoadFailed(cycleId: FireCycleId) extends Command
  case class FireAuthConfirmed(cycleId: FireCycleId) extends Command
  case class FireAuthDenied(
      cycleId: FireCycleId,
      reason: String
  ) extends Command

  // Commandes du cycle de tir
  case class InitiateFireCycle(
      coordinates: TargetCoordinates,
      ammoType: AmmoType = AmmoType.APFSDS
  ) extends Command
  case class AbortFireCycle(cycleId: FireCycleId, reason: String) extends Command
  case object ReloadComplete extends Command
  case object CooldownComplete extends Command

  // Commandes internes (timers)
  private[actors] case class CooldownTimeout(cycleId: FireCycleId) extends Command
  private[actors] case class ReloadTimeout(cycleId: FireCycleId) extends Command

  sealed trait Event extends FCSMessage
  case class FireExecuted(
      cycleId: FireCycleId,
      solution: BallisticSolution,
      ammoType: AmmoType,
      timestamp: Instant = Instant.now()
  ) extends Event
  case class CycleAborted(
      cycleId: FireCycleId,
      reason: String,
      timestamp: Instant = Instant.now()
  ) extends Event

// =============================================================================
// Messages Kafka
// =============================================================================
object KafkaMessages:
  sealed trait KafkaCommand extends FCSMessage
  case class PublishEvent(
      topic: String,
      key: String,
      payload: String,
      timestamp: Instant = Instant.now()
  ) extends KafkaCommand

  case class ConsumedEvent(
      topic: String,
      key: String,
      payload: String,
      offset: Long,
      timestamp: Instant
  ) extends FCSMessage

// =============================================================================
// Messages du SupervisorActor
// =============================================================================
object SupervisorActor:
  sealed trait Command extends FCSMessage
  case object StartSystem extends Command
  case object StopSystem extends Command
  case class ReportError(
      source: String,
      error: Throwable,
      timestamp: Instant = Instant.now()
  ) extends Command
  case class SimulateScenario(scenario: SimulationScenario) extends Command

  /** Scénarios de simulation prédéfinis */
  enum SimulationScenario:
    case NominalFireCycle
    case FireWithoutAuthorization
    case AmmoExhausted
    case ErrorDuringTracking
    case ConcurrentDetections
    case KafkaOffline
