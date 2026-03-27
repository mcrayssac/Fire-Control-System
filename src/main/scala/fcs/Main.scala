package fcs

import akka.actor.typed.ActorSystem
import fcs.actors.{SupervisorActor, KafkaProducerActor}
import fcs.model.SupervisorProtocol.{StartSystem, SimulateScenario, SimulationScenario}
import fcs.petri.*

import scala.io.StdIn

object Main:

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("")
    mode match
      case "akka-demo"   => runAkkaSimulation()
      case "conformance" => runComparison()
      case unknown =>
        val msg = if unknown.nonEmpty then s"Commande inconnue : '$unknown'" else "Aucun mode specifie"
        println(s"""
          |$msg
          |
          |Commandes disponibles (voir README.md) :
          |  sbt compile              — Compilation du projet
          |  sbt test                 — Tests unitaires + verification formelle
          |  sbt "run akka-demo"      — Demonstration interactive du systeme Akka/Kafka
          |  sbt "run conformance"    — Verification de conformite Akka vs modele formel
          |
          |Veuillez reessayer avec l'une des commandes ci-dessus.
          |""".stripMargin)

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
