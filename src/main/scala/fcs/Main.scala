package fcs

import akka.actor.typed.ActorSystem
import fcs.actors.{SupervisorActor, KafkaProducerActor}
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
      case "live"     => runInteractive(args.lift(1))
      case _ =>
        println("""
          |Usage:
          |  sbt "run <mode>"
          |  sbt "run live [verbose|compact]"
          |
          |Modes:
          |  verify    — Analyse formelle du réseau de Pétri (défaut)
          |  simulate  — Simulation Akka/Kafka du système FCS
          |  compare   — Simulation comparée (Akka vs modèle formel)
          |  live      — Panneau interactif (verbose par défaut, compact optionnel)
          |""".stripMargin)

  def runVerification(): Unit =
    println()
    println("FIRE CONTROL SYSTEM - Verification Formelle")
    println()

    val net = FCSPetriNet.build(initialAmmo = 3)
    println(s"Reseau construit : $net")
    println(s"Marquage initial : ${net.initialMarking}")
    println()

    println("-- Exploration de l'espace d'etats (BFS) --")
    val stateSpace = StateSpaceAnalyzer.explore(net)
    println(stateSpace.report(net))

    // Analyse structurelle (P/T-invariants, bornitude, vivacite)
    val structural = InvariantAnalysis.fullAnalysis(net, stateSpace)
    println(InvariantAnalysis.report(net, structural))

    // Invariants metier
    val invReport = InvariantChecker.checkAll(net, stateSpace)
    println(invReport.report)

    // Proprietes LTL
    val ltlResults = LTLVerifier.verifyAll(net, stateSpace)
    println(LTLVerifier.report(ltlResults))

    println("-- Chemin : cycle de tir nominal --")
    StateSpaceAnalyzer.findPath(net, m => m(FCSPetriNet.P_Firing) > 0) match
      case Some(path) =>
        println(s"  Sequence de transitions (${path.size} pas) :")
        path.foreach(t => println(s"    -> ${t.name}"))
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

    // Phase 1 : Simulation multi-scenarios sur le reseau de Petri
    val petriResults = TraceComparator.runAllScenarios()
    println(TraceComparator.report(petriResults))

    // Phase 2 : Simulation Akka avec collecte de traces
    println("-- Simulation Akka avec collecte de traces --")
    println()

    val comparisons = Vector.newBuilder[TraceComparator.TraceComparisonResult]

    // Scenario nominal
    KafkaProducerActor.clearRecordedEvents()
    val system1 = ActorSystem(SupervisorActor(), "fcs-compare-nominal")
    system1 ! StartSystem
    Thread.sleep(500)
    system1 ! SimulateScenario(SimulationScenario.NominalFireCycle)
    Thread.sleep(5000)
    val nominalEvents = KafkaProducerActor.recordedEvents
    system1.terminate()
    Thread.sleep(500)

    comparisons += TraceComparator.compareWithAkkaTrace(
      "Cycle nominal",
      petriResults.head.petriTrace,
      nominalEvents
    )

    // Scenario erreur
    KafkaProducerActor.clearRecordedEvents()
    val system2 = ActorSystem(SupervisorActor(), "fcs-compare-error")
    system2 ! StartSystem
    Thread.sleep(500)
    system2 ! SimulateScenario(SimulationScenario.ErrorDuringTracking)
    Thread.sleep(2000)
    val errorEvents = KafkaProducerActor.recordedEvents
    system2.terminate()
    Thread.sleep(500)

    comparisons += TraceComparator.compareWithAkkaTrace(
      "Erreur systeme",
      petriResults(3).petriTrace, // error cycle scenario
      errorEvents
    )

    // Phase 3 : Comparaison programmatique
    println(TraceComparator.comparisonReport(comparisons.result()))

  def runInteractive(modeArg: Option[String] = None): Unit =
    val net = FCSPetriNet.build(initialAmmo = 3)
    val mode = modeArg.map(InteractiveSimulator.parseOutputMode).getOrElse(InteractiveSimulator.OutputMode.Verbose)
    InteractiveSimulator.run(net, mode)
