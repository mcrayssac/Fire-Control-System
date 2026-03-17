package fcs.kafka

// =============================================================================
// Topics Kafka du système FCS
// Correspond à la branche Kafka du réseau de Pétri (P11, P12)
// =============================================================================

object Topics:
  /** Publié quand une cible est détectée (SensorActor → *) */
  val TargetDetected = "fcs.target.detected"

  /** Confirme le verrouillage balistique (TrackingActor → *) */
  val TargetLocked = "fcs.target.locked"

  /** Autorisation de tir du commandant (CommandActor → *) */
  val FireAuthorized = "fcs.fire.authorized"

  /** Confirmation du tir exécuté (FireControlActor → *) */
  val FireExecuted = "fcs.fire.executed"

  /** Statut du stock de munitions (AmmoActor → *) */
  val AmmoStatus = "fcs.ammo.status"

  /** Erreurs critiques (SupervisorActor → *) */
  val ErrorCritical = "fcs.error.critical"

  /** Topic de consolidation pour l'audit trail */
  val AuditLog = "fcs.audit.log"

  /** Tous les topics du système */
  val All: Seq[String] = Seq(
    TargetDetected,
    TargetLocked,
    FireAuthorized,
    FireExecuted,
    AmmoStatus,
    ErrorCritical,
    AuditLog
  )
