package fcs.petri

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class PetriNetSpec extends AnyFunSuite with Matchers:

  test("Marking arithmetic works correctly"):
    val m1 = Marking(Vector(1, 0, 2))
    val m2 = Marking(Vector(0, 1, 1))

    (m1 + m2) shouldBe Marking(Vector(1, 1, 3))
    (m1 - m2) shouldBe Marking(Vector(1, -1, 1))
    (m1 >= m2) shouldBe false
    (m1 >= Marking(Vector(1, 0, 1))) shouldBe true

  test("Marking.fromMap builds correct vector"):
    val m = Marking.fromMap(5, Map(0 -> 1, 3 -> 2))
    m.tokens shouldBe Vector(1, 0, 0, 2, 0)

  test("Transition is enabled when pre-conditions are met"):
    val t = Transition(0, "test",
      pre = Marking(Vector(1, 0)),
      post = Marking(Vector(0, 1))
    )
    t.isEnabled(Marking(Vector(1, 0))) shouldBe true
    t.isEnabled(Marking(Vector(2, 0))) shouldBe true
    t.isEnabled(Marking(Vector(0, 0))) shouldBe false

  test("Transition fire produces correct marking"):
    val t = Transition(0, "test",
      pre = Marking(Vector(1, 0, 0)),
      post = Marking(Vector(0, 1, 1))
    )
    val result = t.fire(Marking(Vector(1, 0, 0)))
    result shouldBe Some(Marking(Vector(0, 1, 1)))

  test("Transition fire returns None when not enabled"):
    val t = Transition(0, "test",
      pre = Marking(Vector(1, 0)),
      post = Marking(Vector(0, 1))
    )
    t.fire(Marking(Vector(0, 0))) shouldBe None

  test("PetriNet reports enabled transitions"):
    val t1 = Transition(0, "t1",
      pre = Marking(Vector(1, 0)),
      post = Marking(Vector(0, 1))
    )
    val t2 = Transition(1, "t2",
      pre = Marking(Vector(0, 1)),
      post = Marking(Vector(1, 0))
    )
    val net = PetriNet(
      Vector("P0", "P1"),
      Vector(t1, t2),
      Marking(Vector(1, 0))
    )
    net.enabledTransitions(net.initialMarking).map(_.id) shouldBe Vector(0)

  test("PetriNet detects deadlock"):
    val t = Transition(0, "t",
      pre = Marking(Vector(1)),
      post = Marking(Vector(0))
    )
    val net = PetriNet(Vector("P0"), Vector(t), Marking(Vector(0)))
    net.isDeadlock(net.initialMarking) shouldBe true
