package fcs.petri

case class Marking(tokens: Vector[Int]):
  def apply(place: Int): Int = tokens(place)

  def updated(place: Int, value: Int): Marking =
    Marking(tokens.updated(place, value))

  def size: Int = tokens.size

  def >=(other: Marking): Boolean =
    tokens.zip(other.tokens).forall((a, b) => a >= b)

  def -(other: Marking): Marking =
    Marking(tokens.zip(other.tokens).map((a, b) => a - b))

  def +(other: Marking): Marking =
    Marking(tokens.zip(other.tokens).map((a, b) => a + b))

  def isValid: Boolean = tokens.forall(_ >= 0)

  override def toString: String =
    tokens.zipWithIndex
      .filter((v, _) => v > 0)
      .map((v, i) => s"P$i=$v")
      .mkString("M(", ", ", ")")

object Marking:
  def zeros(n: Int): Marking = Marking(Vector.fill(n)(0))

  def fromMap(n: Int, values: Map[Int, Int]): Marking =
    val v = Vector.fill(n)(0)
    Marking(values.foldLeft(v) { case (vec, (place, tokens)) =>
      vec.updated(place, tokens)
    })

case class Transition(
    id: Int,
    name: String,
    pre: Marking,
    post: Marking
):
  def incidence: Marking = post - pre

  def isEnabled(marking: Marking): Boolean = marking >= pre

  def fire(marking: Marking): Option[Marking] =
    if isEnabled(marking) then
      Some(marking - pre + post)
    else
      None

  override def toString: String = s"T$id($name)"

case class PetriNet(
    places: Vector[String],
    transitions: Vector[Transition],
    initialMarking: Marking
):
  require(places.size == initialMarking.size,
    s"Nombre de places (${places.size}) != taille du marquage (${initialMarking.size})")

  val numPlaces: Int = places.size
  val numTransitions: Int = transitions.size

  def incidenceMatrix: Vector[Marking] =
    transitions.map(_.incidence)

  def enabledTransitions(marking: Marking): Vector[Transition] =
    transitions.filter(_.isEnabled(marking))

  def fire(marking: Marking, transition: Transition): Option[Marking] =
    transition.fire(marking)

  def isDeadlock(marking: Marking): Boolean =
    enabledTransitions(marking).isEmpty

  def placeName(index: Int): String =
    if index >= 0 && index < places.size then places(index) else s"P$index"

  override def toString: String =
    s"PetriNet(${numPlaces} places, ${numTransitions} transitions, M₀=$initialMarking)"
