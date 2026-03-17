package fcs.kafka

object Topics:
  val TargetDetected = "fcs.target.detected"
  val TargetLocked   = "fcs.target.locked"
  val FireAuthorized = "fcs.fire.authorized"
  val FireExecuted   = "fcs.fire.executed"
  val AmmoStatus     = "fcs.ammo.status"
  val ErrorCritical  = "fcs.error.critical"
  val AuditLog       = "fcs.audit.log"

  val All: Seq[String] = Seq(
    TargetDetected,
    TargetLocked,
    FireAuthorized,
    FireExecuted,
    AmmoStatus,
    ErrorCritical,
    AuditLog
  )
