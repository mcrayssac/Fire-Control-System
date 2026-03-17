package fcs.petri

object FCSPetriNet:
  val P_Idle            = 0
  val P_TargetDetected  = 1
  val P_TargetLocked    = 2
  val P_AmmoLoaded      = 3
  val P_FireAuthorized  = 4
  val P_ReadyToFire     = 5
  val P_Firing          = 6
  val P_Reloading       = 7
  val P_Cooldown        = 8
  val P_ErrorState      = 9
  val P_AmmoStock       = 10
  val P_KafkaQueue      = 11
  val P_LogRecorded     = 12

  val NumPlaces = 13

  val PlaceNames: Vector[String] = Vector(
    "Idle", "Target_Detected", "Target_Locked", "Ammo_Loaded",
    "Fire_Authorized", "Ready_To_Fire", "Firing", "Reloading",
    "Cooldown", "Error_State", "Ammo_Stock", "Kafka_Queue", "Log_Recorded"
  )

  private def vec(pairs: (Int, Int)*): Marking =
    Marking.fromMap(NumPlaces, pairs.toMap)

  val T_DetectTarget = Transition(
    id = 0, name = "detect_target",
    pre  = vec(P_Idle -> 1),
    post = vec(P_TargetDetected -> 1, P_KafkaQueue -> 1)
  )

  val T_LockTarget = Transition(
    id = 1, name = "lock_target",
    pre  = vec(P_TargetDetected -> 1),
    post = vec(P_TargetLocked -> 1)
  )

  val T_LoadAmmo = Transition(
    id = 2, name = "load_ammo",
    pre  = vec(P_AmmoStock -> 1),
    post = vec(P_AmmoLoaded -> 1)
  )

  val T_AuthorizeFire = Transition(
    id = 3, name = "authorize_fire",
    pre  = vec(P_TargetLocked -> 1),
    post = vec(P_FireAuthorized -> 1)
  )

  val T_ReadySync = Transition(
    id = 4, name = "ready_sync",
    pre  = vec(P_TargetLocked -> 1, P_AmmoLoaded -> 1, P_FireAuthorized -> 1),
    post = vec(P_ReadyToFire -> 1)
  )

  val T_Fire = Transition(
    id = 5, name = "fire",
    pre  = vec(P_ReadyToFire -> 1),
    post = vec(P_Firing -> 1, P_KafkaQueue -> 1)
  )

  val T_EndFire = Transition(
    id = 6, name = "end_fire",
    pre  = vec(P_Firing -> 1),
    post = vec(P_Reloading -> 1, P_Cooldown -> 1)
  )

  val T_ReloadComplete = Transition(
    id = 7, name = "reload_complete",
    pre  = vec(P_Reloading -> 1),
    post = vec(P_Idle -> 1)
  )

  val T_CooldownComplete = Transition(
    id = 8, name = "cooldown_complete",
    pre  = vec(P_Cooldown -> 1),
    post = vec(P_Idle -> 1)
  )

  val T_ErrorDetected = Transition(
    id = 9, name = "error_detected",
    pre  = vec(P_Idle -> 1),
    post = vec(P_ErrorState -> 1)
  )

  val T_ErrorRecovery = Transition(
    id = 10, name = "error_recovery",
    pre  = vec(P_ErrorState -> 1),
    post = vec(P_Idle -> 1)
  )

  val T_KafkaLog = Transition(
    id = 11, name = "kafka_log",
    pre  = vec(P_KafkaQueue -> 1),
    post = vec(P_LogRecorded -> 1)
  )

  def build(initialAmmo: Int = 3): PetriNet =
    val transitions = Vector(
      T_DetectTarget, T_LockTarget, T_LoadAmmo, T_AuthorizeFire,
      T_ReadySync, T_Fire, T_EndFire, T_ReloadComplete,
      T_CooldownComplete, T_ErrorDetected, T_ErrorRecovery, T_KafkaLog
    )

    val m0 = Marking.fromMap(NumPlaces, Map(
      P_Idle -> 1,
      P_AmmoStock -> initialAmmo
    ))

    PetriNet(PlaceNames, transitions, m0)

  def buildWithReadArc(initialAmmo: Int = 3): PetriNet =
    val t3ReadArc = Transition(
      id = 3, name = "authorize_fire",
      pre  = vec(P_TargetLocked -> 1),
      post = vec(P_TargetLocked -> 1, P_FireAuthorized -> 1) // rend le jeton à P2
    )

    val transitions = Vector(
      T_DetectTarget, T_LockTarget, T_LoadAmmo, t3ReadArc,
      T_ReadySync, T_Fire, T_EndFire, T_ReloadComplete,
      T_CooldownComplete, T_ErrorDetected, T_ErrorRecovery, T_KafkaLog
    )

    val m0 = Marking.fromMap(NumPlaces, Map(
      P_Idle -> 1,
      P_AmmoStock -> initialAmmo
    ))

    PetriNet(PlaceNames, transitions, m0)
