package fcs

import akka.actor.typed.ActorSystem
import fcs.actors.{KafkaProducerActor, SupervisorActor}
import fcs.model.SupervisorProtocol.{SimulateScenario, SimulationScenario, StartSystem}
import fcs.petri.*

import scala.io.StdIn

object Main:

  private[fcs] enum ShutdownMode:
    case WaitForEnter, Timeout

  private val ShutdownModeProperty = "fcs.akkaDemo.shutdownMode"
  private val ShutdownModeEnvVar = "FCS_AKKA_DEMO_SHUTDOWN_MODE"

  def main(args: Array[String]): Unit =
    val mode = args.headOption.getOrElse("")
    mode match
      case "akka-demo" | "simulate"    => runAkkaSimulation()
      case "conformance" | "compare"   => runComparison()
      case "live"                       => runInteractive(args.lift(1))
      case unknown =>
        val msg = if unknown.nonEmpty then s"Commande inconnue : '$unknown'" else "Aucun mode specifie"
        println(s"""
          |$msg
          |
          |Commandes disponibles (voir README.md) :
          |  sbt compile                         — Compilation du projet
          |  sbt test                            — Tests unitaires + verification formelle
          |  sbt "run akka-demo"                — Demonstration interactive du systeme Akka/Kafka
          |  sbt "run conformance"              — Verification de conformite Akka vs modele formel
          |  sbt "run live [verbose|compact]"   — Simulateur interactif (verbose par defaut)
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
    waitForEnterOrTimeout(timeoutMs = 10000L)
    system.terminate()

  private def waitForEnterOrTimeout(timeoutMs: Long): Unit =
    shutdownModeFor(sys.env, sys.props, System.console() != null) match
      case ShutdownMode.WaitForEnter =>
        println(waitForEnterMessage)
        StdIn.readLine()
      case ShutdownMode.Timeout =>
        println(timeoutMessage(timeoutMs))
        Thread.sleep(timeoutMs)

  private[fcs] def shutdownModeFor(
      environment: collection.Map[String, String],
      properties: collection.Map[String, String],
      hasConsole: Boolean
  ): ShutdownMode =
    shutdownModeOverride(properties.get(ShutdownModeProperty))
      .orElse(shutdownModeOverride(environment.get(ShutdownModeEnvVar)))
      .getOrElse {
        if isCiEnvironment(environment) || !hasConsole then ShutdownMode.Timeout
        else ShutdownMode.WaitForEnter
      }

  private def shutdownModeOverride(raw: Option[String]): Option[ShutdownMode] =
    raw.map(_.trim.toLowerCase).collect {
      case "wait" | "enter"   => ShutdownMode.WaitForEnter
      case "timeout" | "auto" => ShutdownMode.Timeout
    }

  private def isCiEnvironment(environment: collection.Map[String, String]): Boolean =
    Seq("CI", "GITHUB_ACTIONS").exists { key =>
      environment.get(key).exists(_.trim.nonEmpty)
    }

  private[fcs] def waitForEnterMessage: String =
    "\nAppuyez sur ENTRÉE pour arrêter..."

  private[fcs] def timeoutMessage(timeoutMs: Long): String =
    val seconds = timeoutMs / 1000
    s"\nMode d'arret automatique active. Arret dans ${seconds}s..."

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
