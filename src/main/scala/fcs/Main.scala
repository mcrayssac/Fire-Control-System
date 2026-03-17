package fcs

import akka.actor.typed.ActorSystem
import fcs.actors.SupervisorActor
import fcs.model.SupervisorProtocol.{StartSystem, SimulateScenario, SimulationScenario}
import fcs.petri.*

import scala.io.StdIn

object Main:

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("verify")
    mode match
      case "verify"   => runVerification()
      case "simulate" => runAkkaSimulation()
      case "compare"  => runComparison()
      case _ =>
        println("""
          |Usage: sbt "run <mode>"
          |  verify    — Analyse formelle du réseau de Pétri (défaut)
          |  simulate  — Simulation Akka/Kafka du système FCS
          |  compare   — Simulation comparée (Akka vs modèle formel)
          |""".stripMargin)

  def runVerification(): Unit =
    println()
    println("FIRE CONTROL SYSTEM - Verification Formelle")
    println()

    val net = FCSPetriNet.build(initialAmmo = 3)
    println(s"Réseau construit : $net")
    println(s"Marquage initial : ${net.initialMarking}")
    println()

    println("-- Matrice d'incidence (extrait) --")
    net.transitions.foreach { t =>
      val inc = t.incidence.tokens.zipWithIndex
        .filter((v, _) => v != 0)
        .map((v, i) => s"P${i}=${if v > 0 then "+" else ""}$v")
        .mkString(", ")
      println(f"  ${t.name}%-20s : $inc")
    }
    println()

    println("-- Exploration de l'espace d'etats (BFS) --")
    val stateSpace = StateSpaceAnalyzer.explore(net)
    println(stateSpace.report(net))

    val invReport = InvariantChecker.checkAll(net, stateSpace)
    println(invReport.report)

    val ltlResults = LTLVerifier.verifyAll(net, stateSpace)
    println(LTLVerifier.report(ltlResults))

    println("-- Chemin : cycle de tir nominal --")
    StateSpaceAnalyzer.findPath(net, m => m(FCSPetriNet.P_Firing) > 0) match
      case Some(path) =>
        println(s"  Séquence de transitions (${path.size} pas) :")
        path.foreach(t => println(s"    → ${t.name}"))
      case None =>
        println("  Aucun chemin trouve vers l'etat Firing")
    println()

  def runAkkaSimulation(): Unit =
    println()
    println("FIRE CONTROL SYSTEM - Simulation Akka")
    println()
    val system = ActorSystem(SupervisorActor(), "fcs-system")
    system ! StartSystem
    Thread.sleep(500)
    system ! SimulateScenario(SimulationScenario.NominalFireCycle)
    println("\nAppuyez sur ENTRÉE pour arrêter...")
    StdIn.readLine()
    system.terminate()

  def runComparison(): Unit =
    println()
    println("FIRE CONTROL SYSTEM - Simulation Comparee")
    println()

    val net = FCSPetriNet.build(initialAmmo = 3)
    println("-- Simulation formelle (Reseau de Petri) --")
    println(s"  M₀ = ${net.initialMarking}")

    val nominalSequence = Vector(0, 1, 2, 3, 4, 5, 6, 7, 8)
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
          println(f"  Etape ${step + 1}%2d : ${t.name}%-20s -> NON FRANCHISSABLE")

    net.transitions(11).fire(currentMarking).foreach { next =>
      step += 1
      println(f"  Étape $step%2d : kafka_log             → $next")
      currentMarking = next
    }
    println(s"\n  Marquage final : $currentMarking")
    println()

    println("-- Simulation Akka --")
    val system = ActorSystem(SupervisorActor(), "fcs-compare")
    system ! StartSystem
    Thread.sleep(500)
    system ! SimulateScenario(SimulationScenario.NominalFireCycle)
    Thread.sleep(5000)
    system.terminate()
    println()
    println("Comparer les logs Akka avec la sequence formelle ci-dessus")
