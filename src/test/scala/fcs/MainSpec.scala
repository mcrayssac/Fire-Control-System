package fcs

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class MainSpec extends AnyFunSuite with Matchers:

  test("shutdownModeFor waits for enter when no CI environment variables are set"):
    Main.shutdownModeFor(Map.empty) shouldBe Main.ShutdownMode.WaitForEnter

  test("shutdownModeFor uses timeout when CI is set"):
    Main.shutdownModeFor(Map("CI" -> "true")) shouldBe Main.ShutdownMode.Timeout

  test("shutdownModeFor uses timeout when GITHUB_ACTIONS is set"):
    Main.shutdownModeFor(Map("GITHUB_ACTIONS" -> "true")) shouldBe Main.ShutdownMode.Timeout

  test("shutdownModeFor ignores empty CI environment values"):
    Main.shutdownModeFor(Map("CI" -> "   ", "GITHUB_ACTIONS" -> "")) shouldBe Main.ShutdownMode.WaitForEnter

  test("waitForEnterMessage prompts the user to press enter"):
    Main.waitForEnterMessage shouldBe "\nAppuyez sur ENTRÉE pour arrêter..."

  test("timeoutMessage describes the CI auto-stop delay"):
    Main.timeoutMessage(10000L) shouldBe "\nEnvironnement CI detecte. Arret automatique dans 10s..."
