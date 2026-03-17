package fcs.petri

// =============================================================================
// Définition concrète du Réseau de Pétri FCS
// 13 places (P0..P12), 12 transitions (T0..T11)
// M₀ = (1,0,0,0,0,0,0,0,0,0,N,0,0)
// =============================================================================

object FCSPetriNet:

  // ── Places ─────────────────────────────────────────────────────────────
  val P_Idle            = 0   // P0  : Tank au repos
  val P_TargetDetected  = 1   // P1  : Cible détectée
  val P_TargetLocked    = 2   // P2  : Cible verrouillée
  val P_AmmoLoaded      = 3   // P3  : Munition chargée
  val P_FireAuthorized  = 4   // P4  : Tir autorisé
  val P_ReadyToFire     = 5   // P5  : Préconditions réunies
  val P_Firing          = 6   // P6  : Tir en cours
  val P_Reloading       = 7   // P7  : Rechargement
  val P_Cooldown        = 8   // P8  : Refroidissement
  val P_ErrorState      = 9   // P9  : Erreur critique
  val P_AmmoStock       = 10  // P10 : Stock de munitions (N jetons)
  val P_KafkaQueue      = 11  // P11 : Messages en attente Kafka
  val P_LogRecorded     = 12  // P12 : Événement journalisé

  val NumPlaces = 13

  val PlaceNames: Vector[String] = Vector(
    "Idle", "Target_Detected", "Target_Locked", "Ammo_Loaded",
    "Fire_Authorized", "Ready_To_Fire", "Firing", "Reloading",
    "Cooldown", "Error_State", "Ammo_Stock", "Kafka_Queue", "Log_Recorded"
  )

  // ── Helper pour construire les vecteurs Pre/Post ───────────────────────
  private def vec(pairs: (Int, Int)*): Marking =
    Marking.fromMap(NumPlaces, pairs.toMap)

  // ── Transitions ────────────────────────────────────────────────────────

  /** T0 : detect_target — P0 → P1 + P11 */
  val T_DetectTarget = Transition(
    id = 0, name = "detect_target",
    pre  = vec(P_Idle -> 1),
    post = vec(P_TargetDetected -> 1, P_KafkaQueue -> 1)
  )

  /** T1 : lock_target — P1 → P2 */
  val T_LockTarget = Transition(
    id = 1, name = "lock_target",
    pre  = vec(P_TargetDetected -> 1),
    post = vec(P_TargetLocked -> 1)
  )

  /** T2 : load_ammo — P10 → P3 (consomme 1 jeton de Ammo_Stock) */
  val T_LoadAmmo = Transition(
    id = 2, name = "load_ammo",
    pre  = vec(P_AmmoStock -> 1),
    post = vec(P_AmmoLoaded -> 1)
  )

  /** T3 : authorize_fire — P2 → P4 */
  val T_AuthorizeFire = Transition(
    id = 3, name = "authorize_fire",
    pre  = vec(P_TargetLocked -> 1),
    post = vec(P_FireAuthorized -> 1)
  )

  /** T4 : ready_sync — P2 + P3 + P4 → P5 (synchronisation triple)
    * NOTE : Dans le réseau complet, T3 consomme le jeton de P2.
    * Ici T4 synchronise P2, P3, P4, donc on a besoin d'un jeton dans P2.
    * Le modèle du plan prévoit que T3 produit P4 à partir de P2,
    * MAIS T4 a aussi besoin de P2. Cela implique soit :
    *   (a) T3 ne consomme pas P2 (arc de lecture), soit
    *   (b) T1 produit 2 jetons dans P2
    * On suit la variante (a) : T3 lit P2 sans le consommer,
    * et T4 consomme P2 + P3 + P4.
    */
  val T_ReadySync = Transition(
    id = 4, name = "ready_sync",
    pre  = vec(P_TargetLocked -> 1, P_AmmoLoaded -> 1, P_FireAuthorized -> 1),
    post = vec(P_ReadyToFire -> 1)
  )

  /** T5 : fire — P5 → P6 + P11 */
  val T_Fire = Transition(
    id = 5, name = "fire",
    pre  = vec(P_ReadyToFire -> 1),
    post = vec(P_Firing -> 1, P_KafkaQueue -> 1)
  )

  /** T6 : end_fire — P6 → P7 + P8 (parallèle rechargement + cooldown) */
  val T_EndFire = Transition(
    id = 6, name = "end_fire",
    pre  = vec(P_Firing -> 1),
    post = vec(P_Reloading -> 1, P_Cooldown -> 1)
  )

  /** T7 : reload_complete — P7 → P0 */
  val T_ReloadComplete = Transition(
    id = 7, name = "reload_complete",
    pre  = vec(P_Reloading -> 1),
    post = vec(P_Idle -> 1)
  )

  /** T8 : cooldown_complete — P8 → P0 */
  val T_CooldownComplete = Transition(
    id = 8, name = "cooldown_complete",
    pre  = vec(P_Cooldown -> 1),
    post = vec(P_Idle -> 1)
  )

  /** T9 : error_detected — P_Idle → P9
    * (simplifié : depuis Idle. En réalité, Px → P9 depuis toute place)
    */
  val T_ErrorDetected = Transition(
    id = 9, name = "error_detected",
    pre  = vec(P_Idle -> 1),
    post = vec(P_ErrorState -> 1)
  )

  /** T10 : error_recovery — P9 → P0 */
  val T_ErrorRecovery = Transition(
    id = 10, name = "error_recovery",
    pre  = vec(P_ErrorState -> 1),
    post = vec(P_Idle -> 1)
  )

  /** T11 : kafka_log — P11 → P12 */
  val T_KafkaLog = Transition(
    id = 11, name = "kafka_log",
    pre  = vec(P_KafkaQueue -> 1),
    post = vec(P_LogRecorded -> 1)
  )

  // ── Construction du réseau ─────────────────────────────────────────────

  /** Construit le réseau de Pétri FCS avec N munitions initiales */
  def build(initialAmmo: Int = 3): PetriNet =
    val transitions = Vector(
      T_DetectTarget, T_LockTarget, T_LoadAmmo, T_AuthorizeFire,
      T_ReadySync, T_Fire, T_EndFire, T_ReloadComplete,
      T_CooldownComplete, T_ErrorDetected, T_ErrorRecovery, T_KafkaLog
    )

    // M₀ = (1,0,0,0,0,0,0,0,0,0,N,0,0)
    val m0 = Marking.fromMap(NumPlaces, Map(
      P_Idle -> 1,
      P_AmmoStock -> initialAmmo
    ))

    PetriNet(PlaceNames, transitions, m0)

  /** Version avec T3 qui ne consomme pas P2 (arc de lecture simulé)
    * T3 : ∅ → P4 (gardé par P2 ≥ 1) — modèle comme transition avec pre P2
    * mais on rajoute un jeton dans P2 en post pour simuler l'arc de lecture
    */
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
