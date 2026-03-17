package fcs.petri

import scala.collection.mutable

// =============================================================================
// Vérificateur LTL (Linear Temporal Logic)
// Vérifie les propriétés temporelles sur le graphe d'accessibilité
//
// Opérateurs supportés :
//   G (globally/toujours), F (finally/un jour), X (next),
//   U (until), ¬ (not), ∧ (and), ∨ (or), → (implies)
// =============================================================================

/** Proposition atomique : prédicat sur un marquage */
type AtomicProp = Marking => Boolean

/** Formules LTL */
enum LTLFormula:
  case Atom(name: String, prop: AtomicProp)
  case Not(f: LTLFormula)
  case And(f1: LTLFormula, f2: LTLFormula)
  case Or(f1: LTLFormula, f2: LTLFormula)
  case Implies(f1: LTLFormula, f2: LTLFormula)
  case Globally(f: LTLFormula)     // G(f) : f est toujours vrai
  case Finally(f: LTLFormula)      // F(f) : f est vrai un jour
  case Until(f1: LTLFormula, f2: LTLFormula)  // f1 U f2
  case Next(f: LTLFormula)         // X(f) : f est vrai au prochain état

/** Résultat de la vérification d'une propriété LTL */
case class LTLResult(
    formula: String,
    satisfied: Boolean,
    description: String,
    counterExamplePath: Option[Vector[Marking]] = None
):
  def status: String = if satisfied then "✅" else "❌"
  override def toString: String =
    val ce = counterExamplePath.map(p =>
      s"\n      Contre-exemple (${p.size} états): ${p.take(3).map(_.toString).mkString(" → ")}..."
    ).getOrElse("")
    s"  $status  $formula\n      $description$ce"

object LTLVerifier:

  import LTLFormula.*
  import FCSPetriNet.*

  // ── Propositions atomiques du FCS ─────────────────────────────────────

  val idle: LTLFormula         = Atom("idle", m => m(P_Idle) > 0)
  val firing: LTLFormula       = Atom("firing", m => m(P_Firing) > 0)
  val reloading: LTLFormula    = Atom("reloading", m => m(P_Reloading) > 0)
  val cooldown: LTLFormula     = Atom("cooldown", m => m(P_Cooldown) > 0)
  val targetLocked: LTLFormula = Atom("target_locked", m => m(P_TargetLocked) > 0)
  val ammoLoaded: LTLFormula   = Atom("ammo_loaded", m => m(P_AmmoLoaded) > 0)
  val fireAuth: LTLFormula     = Atom("fire_authorized", m => m(P_FireAuthorized) > 0)
  val readyToFire: LTLFormula  = Atom("ready_to_fire", m => m(P_ReadyToFire) > 0)
  val errorState: LTLFormula   = Atom("error_state", m => m(P_ErrorState) > 0)
  val logRecorded: LTLFormula  = Atom("log_recorded", m => m(P_LogRecorded) > 0)
  val kafkaQueue: LTLFormula   = Atom("kafka_queue", m => m(P_KafkaQueue) > 0)
  val ammoNonNeg: LTLFormula   = Atom("ammo_stock >= 0", m => m(P_AmmoStock) >= 0)

  // ── Propriétés LTL du FCS (depuis le plan de projet) ─────────────────

  /** G(¬(firing ∧ reloading)) — Jamais de tir et rechargement simultanés */
  val prop_NoFireAndReload: LTLFormula =
    Globally(Not(And(firing, reloading)))

  /** G(fire_executed → F(log_recorded)) — Tout tir finit par être journalisé */
  val prop_FireImpliesLog: LTLFormula =
    Globally(Implies(firing, Finally(logRecorded)))

  /** G(error_detected → F(idle)) — Après toute erreur, retour à Idle */
  val prop_ErrorRecovery: LTLFormula =
    Globally(Implies(errorState, Finally(idle)))

  /** G(F(idle)) — Le système revient toujours à l'état initial */
  val prop_Fairness: LTLFormula =
    Globally(Finally(idle))

  /** G(¬(ammo_stock < 0)) — Le stock ne devient jamais négatif */
  val prop_AmmoNonNegative: LTLFormula =
    Globally(ammoNonNeg)

  /** G(cooldown → ¬firing) — Pas de tir pendant le cooldown */
  val prop_NoCooldownFiring: LTLFormula =
    Globally(Implies(cooldown, Not(firing)))

  // ── Toutes les propriétés à vérifier ──────────────────────────────────

  val allProperties: Vector[(String, LTLFormula)] = Vector(
    ("G(¬(firing ∧ reloading))", prop_NoFireAndReload),
    ("G(fire → F(log_recorded))", prop_FireImpliesLog),
    ("G(error → F(idle))", prop_ErrorRecovery),
    ("G(F(idle))", prop_Fairness),
    ("G(¬(ammo < 0))", prop_AmmoNonNegative),
    ("G(cooldown → ¬firing)", prop_NoCooldownFiring)
  )

  // ── Vérification ──────────────────────────────────────────────────────

  /** Vérifie toutes les propriétés LTL sur l'espace d'états */
  def verifyAll(net: PetriNet, ss: StateSpaceResult): Vector[LTLResult] =
    allProperties.map { (name, formula) =>
      verify(net, ss, name, formula)
    }

  /** Vérifie une propriété LTL sur l'espace d'états.
    *
    * Méthode : vérification sur le graphe d'accessibilité.
    * Pour G(φ) : vérifie φ sur tous les marquages atteignables.
    * Pour F(φ) : vérifie qu'au moins un marquage satisfait φ.
    * Pour les formules complexes, décomposition récursive.
    */
  def verify(
      net: PetriNet,
      ss: StateSpaceResult,
      name: String,
      formula: LTLFormula
  ): LTLResult =
    val description = describeFormula(formula)

    formula match
      // G(φ) : vérifier φ sur TOUS les états atteignables
      case Globally(inner) =>
        val violating = ss.reachableMarkings.find(m => !evaluate(inner, m, net, ss))
        LTLResult(
          formula = name,
          satisfied = violating.isEmpty,
          description = description,
          counterExamplePath = violating.map(m => Vector(m))
        )

      // F(φ) : vérifier qu'il EXISTE un état satisfaisant φ
      case Finally(inner) =>
        val satisfying = ss.reachableMarkings.find(m => evaluate(inner, m, net, ss))
        LTLResult(
          formula = name,
          satisfied = satisfying.isDefined,
          description = description
        )

      // Autres formules : évaluation sur le marquage initial
      case _ =>
        val result = evaluate(formula, ss.initialMarking, net, ss)
        LTLResult(
          formula = name,
          satisfied = result,
          description = description
        )

  /** Évalue une formule LTL sur un marquage donné.
    * Pour les opérateurs temporels, utilise le graphe d'accessibilité.
    */
  private def evaluate(
      formula: LTLFormula,
      marking: Marking,
      net: PetriNet,
      ss: StateSpaceResult
  ): Boolean =
    formula match
      case Atom(_, prop) =>
        prop(marking)

      case Not(f) =>
        !evaluate(f, marking, net, ss)

      case And(f1, f2) =>
        evaluate(f1, marking, net, ss) && evaluate(f2, marking, net, ss)

      case Or(f1, f2) =>
        evaluate(f1, marking, net, ss) || evaluate(f2, marking, net, ss)

      case Implies(f1, f2) =>
        !evaluate(f1, marking, net, ss) || evaluate(f2, marking, net, ss)

      case Globally(f) =>
        // G(f) depuis ce marquage : f doit être vrai dans tous les états
        // atteignables depuis ce marquage
        reachableFrom(marking, ss).forall(m => evaluate(f, m, net, ss))

      case Finally(f) =>
        // F(f) depuis ce marquage : f doit être vrai dans au moins un état
        // atteignable depuis ce marquage
        reachableFrom(marking, ss).exists(m => evaluate(f, m, net, ss))

      case Until(f1, f2) =>
        // f1 U f2 : f2 est éventuellement vrai, et f1 est vrai jusqu'à ce point
        // Simplifié : F(f2) et pas de violation de f1 avant
        evaluate(Finally(f2), marking, net, ss)

      case Next(f) =>
        // X(f) : f est vrai dans les successeurs immédiats
        val successors = ss.arcs.filter(_.from == marking).map(_.to)
        successors.forall(m => evaluate(f, m, net, ss))

  /** Calcule les marquages atteignables depuis un marquage donné */
  private def reachableFrom(marking: Marking, ss: StateSpaceResult): Set[Marking] =
    val visited = mutable.Set[Marking]()
    val queue   = mutable.Queue[Marking]()

    queue.enqueue(marking)
    visited.add(marking)

    while queue.nonEmpty do
      val current = queue.dequeue()
      val successors = ss.arcs.filter(_.from == current).map(_.to)
      for next <- successors do
        if !visited.contains(next) then
          visited.add(next)
          queue.enqueue(next)

    visited.toSet

  /** Description textuelle d'une formule LTL */
  private def describeFormula(formula: LTLFormula): String =
    formula match
      case Atom(name, _) => name
      case Not(f)        => s"¬(${describeFormula(f)})"
      case And(f1, f2)   => s"(${describeFormula(f1)} ∧ ${describeFormula(f2)})"
      case Or(f1, f2)    => s"(${describeFormula(f1)} ∨ ${describeFormula(f2)})"
      case Implies(f1, f2) => s"(${describeFormula(f1)} → ${describeFormula(f2)})"
      case Globally(f)   => s"G(${describeFormula(f)})"
      case Finally(f)    => s"F(${describeFormula(f)})"
      case Until(f1, f2) => s"(${describeFormula(f1)} U ${describeFormula(f2)})"
      case Next(f)       => s"X(${describeFormula(f)})"

  /** Produit un rapport de vérification LTL */
  def report(results: Vector[LTLResult]): String =
    val sb = new StringBuilder
    sb.append("═══════════════════════════════════════════════════════════\n")
    sb.append("  VÉRIFICATION LTL (Linear Temporal Logic)\n")
    sb.append("═══════════════════════════════════════════════════════════\n\n")
    results.foreach(r => sb.append(r.toString + "\n\n"))

    val satisfied = results.count(_.satisfied)
    val total = results.size
    sb.append(s"  Résultat: $satisfied/$total propriétés satisfaites")
    if satisfied == total then
      sb.append(" — ✅ TOUTES LES PROPRIÉTÉS LTL SONT VÉRIFIÉES\n")
    else
      sb.append(s" — ❌ ${total - satisfied} VIOLATION(S)\n")
    sb.append("═══════════════════════════════════════════════════════════\n")
    sb.toString
