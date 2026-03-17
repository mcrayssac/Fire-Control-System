package fcs

import akka.actor.typed.ActorSystem
import fcs.actors.SupervisorActor
import fcs.model.SupervisorActor.{StartSystem, SimulateScenario, SimulationScenario}
import fcs.petri.*

import scala.io.StdIn

// =============================================================================
// Fire Control System — Point d'entrée principal
// =============================================================================

object Main:

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("verify")

    mode match
      case "verify"   => runVerification()
      case "simulate" => runAkkaSimulation()
      case "compare"  => runComparison()
      case "help" | _ =>
        println("""
          |Usage: sbt "run <mode>"
          |
          |Modes:
          |  verify    — Analyse formelle du réseau de Pétri (défaut)
          |  simulate  — Simulation Akka/Kafka du système FCS
          |  compare   — Simulation comparée (Akka vs modèle formel)
          |  help      — Affiche cette aide
          |""".stripMargin)

  // ═══════════════════════════════════════════════════════════════════════
  // Mode 1 : Vérification formelle du réseau de Pétri
  // ═══════════════════════════════════════════════════════════════════════
  def runVerification(): Unit =
    println()
    println("╔═══════════════════════════════════════════════════════════╗")
    println("║    FIRE CONTROL SYSTEM — Vérification Formelle          ║")
    println("║    Réseaux de Pétri + LTL                               ║")
    println("╚═══════════════════════════════════════════════════════════╝")
    println()

    // Construction du réseau avec arc de lecture pour T3
    val net = FCSPetriNet.buildWithReadArc(initialAmmo = 3)
    println(s"Réseau construit : $net")
    println(s"Marquage initial : ${net.initialMarking}")
    println()

    // --- Matrice d'incidence ---
    println("── Matrice d'incidence (extrait) ──")
    net.transitions.foreach { t =>
      val inc = t.incidence.tokens.zipWithIndex
        .filter((v, _) => v != 0)
        .map((v, i) => s"P${i}=${if v > 0 then "+" else ""}$v")
        .mkString(", ")
      println(f"  ${t.name}%-20s : $inc")
    }
    println()

    // --- Exploration de l'espace d'états ---
    println("── Exploration de l'espace d'états (BFS) ──")
    val stateSpace = StateSpaceAnalyzer.explore(net)
    println(stateSpace.report(net))

    // --- Vérification des invariants ---
    val invReport = InvariantChecker.checkAll(net, stateSpace)
    println(invReport.report)

    // --- Vérification LTL ---
    val ltlResults = LTLVerifier.verifyAll(net, stateSpace)
    println(LTLVerifier.report(ltlResults))

    // --- Recherche de chemin : cycle de tir nominal ---
    println("── Chemin : cycle de tir nominal ──")
    val firingPath = StateSpaceAnalyzer.findPath(
      net,
      target = m => m(FCSPetriNet.P_Firing) > 0
    )
    firingPath match
      case Some(path) =>
        println(s"  Séquence de transitions (${path.size} pas) :")
        path.foreach(t => println(s"    → ${t.name}"))
      case None =>
        println("  ⚠️  Aucun chemin trouvé vers l'état Firing")
    println()

  // ═══════════════════════════════════════════════════════════════════════
  // Mode 2 : Simulation Akka du système
  // ═══════════════════════════════════════════════════════════════════════
  def runAkkaSimulation(): Unit =
    println()
    println("╔═══════════════════════════════════════════════════════════╗")
    println("║    FIRE CONTROL SYSTEM — Simulation Akka                ║")
    println("╚═══════════════════════════════════════════════════════════╝")
    println()

    val system = ActorSystem(
      fcs.actors.SupervisorActor(),
      "fcs-system"
    )

    system ! StartSystem

    // Simuler un cycle de tir nominal
    Thread.sleep(500)
    system ! SimulateScenario(SimulationScenario.NominalFireCycle)

    println("\nAppuyez sur ENTRÉE pour arrêter le système...")
    StdIn.readLine()
    system.terminate()

  // ═══════════════════════════════════════════════════════════════════════
  // Mode 3 : Simulation comparée Akka vs Réseau de Pétri
  // ═══════════════════════════════════════════════════════════════════════
  def runComparison(): Unit =
    println()
    println("╔═══════════════════════════════════════════════════════════╗")
    println("║    FIRE CONTROL SYSTEM — Simulation Comparée            ║")
    println("║    Akka/Kafka vs Modèle Formel (Réseau de Pétri)        ║")
    println("╚═══════════════════════════════════════════════════════════╝")
    println()

    // --- Côté formel : simuler le cycle dans le RdP ---
    val net = FCSPetriNet.buildWithReadArc(initialAmmo = 3)
    println("── Simulation formelle (Réseau de Pétri) ──")
    println(s"  M₀ = ${net.initialMarking}")

    // Séquence nominale : T0 → T1 → T2 → T3 → T4 → T5 → T6 → T7 + T8
    val nominalSequence = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8)  // indices des transitions
    var currentMarking = net.initialMarking
    var step = 0

    for tIdx <- nominalSequence do
      val t = net.transitions(tIdx)
      t.fire(currentMarking) match
        case Some(next) =>
          step += 1
          println(f"  Étape $step%2d : ${t.name}%-20s → $next")
          currentMarking = next
        case None =>
          println(f"  Étape ${step + 1}%2d : ${t.name}%-20s → ⚠️ NON FRANCHISSABLE depuis $currentMarking")

    // Aussi T11 pour le logging Kafka
    net.transitions(11).fire(currentMarking) match
      case Some(next) =>
        step += 1
        println(f"  Étape $step%2d : kafka_log             → $next")
        currentMarking = next
      case None =>

    println(s"\n  Marquage final : $currentMarking")
    println()

    // --- Côté Akka : lancer la simulation ---
    println("── Simulation Akka ──")
    val system = ActorSystem(fcs.actors.SupervisorActor(), "fcs-compare")
    system ! StartSystem
    Thread.sleep(500)
    system ! SimulateScenario(SimulationScenario.NominalFireCycle)

    Thread.sleep(5000) // Attendre que le cycle se termine
    println("\n  Simulation Akka terminée")

    system.terminate()

    println()
    println("══════════════════════════════════════════════════════════════")
    println("  Comparer les logs Akka ci-dessus avec la séquence formelle")
    println("  pour vérifier la correspondance des transitions")
    println("══════════════════════════════════════════════════════════════")
