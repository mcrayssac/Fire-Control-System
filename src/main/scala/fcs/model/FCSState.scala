package fcs.model

import java.time.Instant

// =============================================================================
// État du système FCS
// Correspond aux places du réseau de Pétri
// =============================================================================

/** État global du système de contrôle de tir */
enum FCSPhase:
  case Idle            // P0 : au repos
  case TargetDetected  // P1 : cible détectée
  case TargetLocked    // P2 : cible verrouillée
  case AmmoLoaded      // P3 : munition chargée
  case FireAuthorized  // P4 : tir autorisé
  case ReadyToFire     // P5 : préconditions réunies
  case Firing          // P6 : tir en cours
  case Reloading       // P7 : rechargement
  case Cooldown        // P8 : refroidissement
  case Error           // P9 : erreur critique

/** État interne du FireControlActor
  *
  * Suit les préconditions de tir (correspond à la synchronisation T4)
  */
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
  /** Vérifie si toutes les préconditions de tir sont réunies (T4 sync) */
  def isReadyToFire: Boolean =
    targetLocked && ammoLoaded && fireAuthorized

  /** Durée du cycle en millisecondes */
  def durationMs: Option[Long] =
    endTime.map(end => java.time.Duration.between(startTime, end).toMillis)

/** Compteur de munitions (correspond à P10 : Ammo_Stock) */
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
