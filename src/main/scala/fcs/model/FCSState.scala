package fcs.model

import java.time.Instant

enum FCSPhase:
  case Idle
  case TargetDetected
  case TargetLocked
  case AmmoLoaded
  case FireAuthorized
  case ReadyToFire
  case Firing
  case Reloading
  case Cooldown
  case Error

case class FireCycleState(
    cycleId: FireCycleId,
    phase: FCSPhase = FCSPhase.Idle,
    targetLocked: Boolean = false,
    ammoLoaded: Boolean = false,
    fireAuthorized: Boolean = false,
    solution: Option[BallisticSolution] = None,
    ammoType: Option[AmmoType] = None,
    coordinates: Option[TargetCoordinates] = None,
    startTime: Instant = Instant.now(),
    endTime: Option[Instant] = None,
    error: Option[String] = None
):
  def isReadyToFire: Boolean =
    targetLocked && ammoLoaded && fireAuthorized

  def durationMs: Option[Long] =
    endTime.map(end => java.time.Duration.between(startTime, end).toMillis)

case class AmmoStock(
    stock: Map[AmmoType, Int] = Map(
      AmmoType.APFSDS -> 10,
      AmmoType.HEAT -> 8,
      AmmoType.HESH -> 6,
      AmmoType.HE -> 12
    )
):
  def total: Int = stock.values.sum

  def available(ammoType: AmmoType): Int =
    stock.getOrElse(ammoType, 0)

  def consume(ammoType: AmmoType): Option[AmmoStock] =
    val current = available(ammoType)
    if current > 0 then
      Some(copy(stock = stock.updated(ammoType, current - 1)))
    else
      None

  def isEmpty(ammoType: AmmoType): Boolean =
    available(ammoType) <= 0

  def isCompletelyEmpty: Boolean =
    stock.values.forall(_ <= 0)
