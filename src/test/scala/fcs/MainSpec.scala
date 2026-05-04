package fcs

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MainSpec extends AnyFunSuite with Matchers:

  test("shutdownModeFor waits for enter when no CI vars are set and a console is present"):
    Main.shutdownModeFor(Map.empty, Map.empty, hasConsole = true) shouldBe Main.ShutdownMode.WaitForEnter

  test("shutdownModeFor uses timeout when no CI vars are set and no console is present"):
    Main.shutdownModeFor(Map.empty, Map.empty, hasConsole = false) shouldBe Main.ShutdownMode.Timeout

  test("shutdownModeFor uses timeout when CI is set"):
    Main.shutdownModeFor(Map("CI" -> "true"), Map.empty, hasConsole = true) shouldBe Main.ShutdownMode.Timeout

  test("shutdownModeFor uses timeout when GITHUB_ACTIONS is set"):
    Main.shutdownModeFor(Map("GITHUB_ACTIONS" -> "true"), Map.empty, hasConsole = true) shouldBe Main.ShutdownMode.Timeout

  test("shutdownModeFor ignores empty CI environment values"):
    Main.shutdownModeFor(
      Map("CI" -> "   ", "GITHUB_ACTIONS" -> ""),
      Map.empty,
      hasConsole = true
    ) shouldBe Main.ShutdownMode.WaitForEnter

  test("shutdownModeFor lets the system property force wait even in CI"):
    Main.shutdownModeFor(
      Map("CI" -> "true"),
      Map("fcs.akkaDemo.shutdownMode" -> "wait"),
      hasConsole = false
    ) shouldBe Main.ShutdownMode.WaitForEnter

  test("shutdownModeFor lets the system property force timeout when a console is present"):
    Main.shutdownModeFor(
      Map.empty,
      Map("fcs.akkaDemo.shutdownMode" -> "timeout"),
      hasConsole = true
    ) shouldBe Main.ShutdownMode.Timeout

  test("shutdownModeFor uses the environment override when the system property is absent"):
    Main.shutdownModeFor(
      Map("FCS_AKKA_DEMO_SHUTDOWN_MODE" -> "enter"),
      Map.empty,
      hasConsole = false
    ) shouldBe Main.ShutdownMode.WaitForEnter

  test("shutdownModeFor ignores invalid override values and falls back to the heuristic"):
    Main.shutdownModeFor(
      Map("FCS_AKKA_DEMO_SHUTDOWN_MODE" -> "later"),
      Map("fcs.akkaDemo.shutdownMode" -> "unknown"),
      hasConsole = false
    ) shouldBe Main.ShutdownMode.Timeout

  test("waitForEnterMessage prompts the user to press enter"):
    Main.waitForEnterMessage shouldBe "\nAppuyez sur ENTRÉE pour arrêter..."

  test("timeoutMessage describes the generic auto-stop delay"):
    Main.timeoutMessage(10000L) shouldBe "\nMode d'arret automatique active. Arret dans 10s..."
