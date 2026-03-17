package fcs.petri

// =============================================================================
// Modèle formel du Réseau de Pétri
// RdP = (P, T, Pre, Post, M₀)
// P = {P0..P12}, T = {T0..T11}
// =============================================================================

/** Marquage : vecteur d'entiers indexé par les places */
case class Marking(tokens: Vector[Int]):
  def apply(place: Int): Int = tokens(place)

  def updated(place: Int, value: Int): Marking =
    Marking(tokens.updated(place, value))

  def size: Int = tokens.size

  /** Vérifie si un marquage est ≥ à un autre (composante par composante) */
  def >=(other: Marking): Boolean =
    tokens.zip(other.tokens).forall((a, b) => a >= b)

  /** Soustrait un marquage (Pre) */
  def -(other: Marking): Marking =
    Marking(tokens.zip(other.tokens).map((a, b) => a - b))

  /** Ajoute un marquage (Post) */
  def +(other: Marking): Marking =
    Marking(tokens.zip(other.tokens).map((a, b) => a + b))

  /** Vérifie que tous les jetons sont ≥ 0 */
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

/** Transition du réseau de Pétri */
case class Transition(
    id: Int,
    name: String,
    pre: Marking,   // jetons consommés (arcs entrants)
    post: Marking   // jetons produits (arcs sortants)
):
  /** Effet net de la transition : C[t] = Post[t] - Pre[t] */
  def incidence: Marking = post - pre

  /** Vérifie si la transition est franchissable depuis un marquage */
  def isEnabled(marking: Marking): Boolean = marking >= pre

  /** Franchit la transition depuis un marquage */
  def fire(marking: Marking): Option[Marking] =
    if isEnabled(marking) then
      Some(marking - pre + post)
    else
      None

  override def toString: String = s"T$id($name)"

/** Réseau de Pétri complet */
case class PetriNet(
    places: Vector[String],
    transitions: Vector[Transition],
    initialMarking: Marking
):
  require(places.size == initialMarking.size,
    s"Nombre de places (${places.size}) != taille du marquage (${initialMarking.size})")

  val numPlaces: Int = places.size
  val numTransitions: Int = transitions.size

  /** Matrice d'incidence C = Post - Pre */
  def incidenceMatrix: Vector[Marking] =
    transitions.map(_.incidence)

  /** Transitions franchissables depuis un marquage */
  def enabledTransitions(marking: Marking): Vector[Transition] =
    transitions.filter(_.isEnabled(marking))

  /** Franchit une transition donnée */
  def fire(marking: Marking, transition: Transition): Option[Marking] =
    transition.fire(marking)

  /** Vérifie si un marquage est un deadlock */
  def isDeadlock(marking: Marking): Boolean =
    enabledTransitions(marking).isEmpty

  /** Nom d'une place par son index */
  def placeName(index: Int): String =
    if index >= 0 && index < places.size then places(index) else s"P$index"

  override def toString: String =
    s"PetriNet(${numPlaces} places, ${numTransitions} transitions, M₀=$initialMarking)"
