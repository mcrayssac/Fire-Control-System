package fcs.model

import akka.actor.typed.ActorRef
import java.time.Instant
import java.util.UUID


trait FCSMessage extends Serializable

case class FireCycleId(value: String = UUID.randomUUID().toString)

case class TargetCoordinates(
    x: Double,
    y: Double,
    z: Double,
    bearing: Double,
    range: Double
)

case class BallisticSolution(
    elevation: Double,
    windage: Double,
    timeOfFlight: Double,
    confidence: Double
)

enum AmmoType:
  case APFSDS
  case HEAT
  case HESH
  case HE

object SensorProtocol:
  trait Command extends FCSMessage
  case object StartScanning extends Command
  case object StopScanning extends Command
  case class SimulateDetection(coords: TargetCoordinates) extends Command

  trait Event extends FCSMessage
  case class TargetDetected(
      cycleId: FireCycleId,
      coordinates: TargetCoordinates,
      timestamp: Instant = Instant.now()
  ) extends Event

object TrackingProtocol:
  trait Command extends FCSMessage
  case class TrackTarget(
      cycleId: FireCycleId,
      coordinates: TargetCoordinates,
      replyTo: ActorRef[FireControlProtocol.Command]
  ) extends Command
  case class LoseTrack(cycleId: FireCycleId) extends Command

  trait Event extends FCSMessage
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

object AmmoProtocol:
  trait Command extends FCSMessage
  case class LoadAmmo(
      cycleId: FireCycleId,
      ammoType: AmmoType,
      replyTo: ActorRef[FireControlProtocol.Command]
  ) extends Command
  case class ConsumeAmmo(cycleId: FireCycleId) extends Command
  case object QueryStock extends Command

  trait Event extends FCSMessage
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

object CommandProtocol:
  trait Command extends FCSMessage
  case class RequestAuthorization(
      cycleId: FireCycleId,
      solution: BallisticSolution,
      replyTo: ActorRef[FireControlProtocol.Command]
  ) extends Command
  case class RevokeAuthorization(cycleId: FireCycleId) extends Command

  case class ROE(
      weaponsFree: Boolean = false,
      maxRange: Double = 4000.0,
      minConfidence: Double = 0.8
  )

  trait Event extends FCSMessage
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

object FireControlProtocol:
  trait Command extends FCSMessage

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

  case class InitiateFireCycle(
      coordinates: TargetCoordinates,
      ammoType: AmmoType = AmmoType.APFSDS
  ) extends Command
  case class AbortFireCycle(cycleId: FireCycleId, reason: String) extends Command
  case object ReloadComplete extends Command
  case object CooldownComplete extends Command

  case class CooldownTimeout(cycleId: FireCycleId) extends Command
  case class ReloadTimeout(cycleId: FireCycleId) extends Command

  trait Event extends FCSMessage
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

object KafkaMessages:
  trait KafkaCommand extends FCSMessage
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

object SupervisorProtocol:
  trait Command extends FCSMessage
  case object StartSystem extends Command
  case object StopSystem extends Command
  case class ReportError(
      source: String,
      error: Throwable,
      timestamp: Instant = Instant.now()
  ) extends Command
  case class SimulateScenario(scenario: SimulationScenario) extends Command

  enum SimulationScenario:
    case NominalFireCycle
    case FireWithoutAuthorization
    case AmmoExhausted
    case ErrorDuringTracking
    case ConcurrentDetections
    case KafkaOffline
