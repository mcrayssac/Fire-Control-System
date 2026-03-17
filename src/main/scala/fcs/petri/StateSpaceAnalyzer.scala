package fcs.petri

import scala.collection.mutable

case class StateArc(
    from: Marking,
    transition: Transition,
    to: Marking
)

case class StateSpaceResult(
    reachableMarkings: Set[Marking],
    arcs: Vector[StateArc],
    deadlocks: Set[Marking],
    initialMarking: Marking
):
  def numStates: Int = reachableMarkings.size
  def numArcs: Int = arcs.size
  def numDeadlocks: Int = deadlocks.size
  def hasDeadlocks: Boolean = deadlocks.nonEmpty

  lazy val successorMap: Map[Marking, Vector[Marking]] =
    arcs.groupBy(_.from).map((k, v) => k -> v.map(_.to))

  lazy val predecessorMap: Map[Marking, Vector[Marking]] =
    arcs.groupBy(_.to).map((k, v) => k -> v.map(_.from))

  def report(net: PetriNet): String =
    val sb = new StringBuilder
    sb.append("===========================================================\n")
    sb.append("  ANALYSE DE L'ESPACE D'ÉTATS\n")
    sb.append("===========================================================\n")
    sb.append(s"  Réseau       : ${net.numPlaces} places, ${net.numTransitions} transitions\n")
    sb.append(s"  Marquage M₀  : $initialMarking\n")
    sb.append(s"  États att.   : $numStates\n")
    sb.append(s"  Arcs          : $numArcs\n")
    sb.append(s"  Deadlocks    : $numDeadlocks\n")

    if hasDeadlocks then
      sb.append("\n  DEADLOCKS DETECTES :\n")
      deadlocks.foreach { m =>
        sb.append(s"    • $m\n")
      }
    else
      sb.append("\n  Aucun deadlock detecte\n")

    sb.append("===========================================================\n")
    sb.toString

object StateSpaceAnalyzer:

  def explore(net: PetriNet, maxStates: Int = 100_000): StateSpaceResult =
    val visited = mutable.Set[Marking]()
    val queue   = mutable.Queue[Marking]()
    val arcs    = mutable.ArrayBuffer[StateArc]()
    val deadlocks = mutable.Set[Marking]()

    val m0 = net.initialMarking
    queue.enqueue(m0)
    visited.add(m0)

    while queue.nonEmpty && visited.size < maxStates do
      val current = queue.dequeue()
      val enabled = net.enabledTransitions(current)

      if enabled.isEmpty then
        deadlocks.add(current)

      for t <- enabled do
        t.fire(current) match
          case Some(next) if next.isValid =>
            arcs += StateArc(current, t, next)
            if !visited.contains(next) then
              visited.add(next)
              queue.enqueue(next)
          case _ =>
    end while

    StateSpaceResult(
      reachableMarkings = visited.toSet,
      arcs = arcs.toVector,
      deadlocks = deadlocks.toSet,
      initialMarking = m0
    )

  def findMarking(
      net: PetriNet,
      predicate: Marking => Boolean,
      maxStates: Int = 100_000
  ): Option[Marking] =
    val visited = mutable.Set[Marking]()
    val stack   = mutable.Stack[Marking]()

    stack.push(net.initialMarking)

    while stack.nonEmpty && visited.size < maxStates do
      val current = stack.pop()
      if !visited.contains(current) then
        visited.add(current)
        if predicate(current) then return Some(current)

        for t <- net.enabledTransitions(current) do
          t.fire(current).foreach { next =>
            if next.isValid && !visited.contains(next) then
              stack.push(next)
          }

    None

  def findPath(
      net: PetriNet,
      target: Marking => Boolean,
      maxDepth: Int = 50
  ): Option[Vector[Transition]] =
    case class Node(marking: Marking, path: Vector[Transition])

    val visited = mutable.Set[Marking]()
    val queue   = mutable.Queue[Node]()

    queue.enqueue(Node(net.initialMarking, Vector.empty))
    visited.add(net.initialMarking)

    while queue.nonEmpty do
      val Node(current, path) = queue.dequeue()

      if target(current) then return Some(path)

      if path.size < maxDepth then
        for t <- net.enabledTransitions(current) do
          t.fire(current).foreach { next =>
            if next.isValid && !visited.contains(next) then
              visited.add(next)
              queue.enqueue(Node(next, path :+ t))
          }

    None
