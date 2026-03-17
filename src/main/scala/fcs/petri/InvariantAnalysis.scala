package fcs.petri

import scala.collection.mutable

object InvariantAnalysis:

  // === Data Types ===

  enum LivenessLevel(val code: String, val description: String):
    case L0 extends LivenessLevel("L0", "Morte — jamais franchissable")
    case L1 extends LivenessLevel("L1", "Potentiellement franchissable")
    case L4 extends LivenessLevel("L4", "Vivante — franchissable depuis tout état")

  case class BoundednessResult(
      placeBounds: Map[String, Int],
      k: Int,
      isBounded: Boolean,
      isSafe: Boolean
  )

  case class LivenessResult(
      transitionLevels: Map[String, LivenessLevel],
      isLive: Boolean,
      deadTransitions: Set[String]
  )

  case class InvariantReport(
      pInvariants: Vector[Vector[Int]],
      tInvariants: Vector[Vector[Int]],
      incidenceMatrix: Vector[Vector[Int]],
      boundedness: BoundednessResult,
      liveness: LivenessResult
  )

  // === Public API ===

  def fullAnalysis(net: PetriNet, ss: StateSpaceResult): InvariantReport =
    InvariantReport(
      pInvariants = computePInvariants(net),
      tInvariants = computeTInvariants(net),
      incidenceMatrix = computeIncidenceMatrix(net),
      boundedness = analyzeBoundedness(net, ss),
      liveness = analyzeLiveness(net, ss)
    )

  def computeIncidenceMatrix(net: PetriNet): Vector[Vector[Int]] =
    (0 until net.numPlaces).map { p =>
      (0 until net.numTransitions).map { t =>
        val tr = net.transitions(t)
        tr.post.tokens(p) - tr.pre.tokens(p)
      }.toVector
    }.toVector

  def computePInvariants(net: PetriNet): Vector[Vector[Int]] =
    val c = computeIncidenceMatrix(net)
    val ct = transpose(c)
    val basis = integerNullSpace(ct)
    findNonNegativeCombinations(basis)

  def computeTInvariants(net: PetriNet): Vector[Vector[Int]] =
    val c = computeIncidenceMatrix(net)
    val basis = integerNullSpace(c)
    findNonNegativeCombinations(basis)

  def analyzeBoundedness(net: PetriNet, ss: StateSpaceResult): BoundednessResult =
    val bounds = (0 until net.numPlaces).map { p =>
      val maxTokens = ss.reachableMarkings.map(_(p)).max
      net.placeName(p) -> maxTokens
    }.toMap
    val k = if bounds.isEmpty then 0 else bounds.values.max
    BoundednessResult(placeBounds = bounds, k = k, isBounded = true, isSafe = k <= 1)

  def analyzeLiveness(net: PetriNet, ss: StateSpaceResult): LivenessResult =
    val levels = net.transitions.map { t =>
      val enabledMarkings = ss.reachableMarkings.filter(t.isEnabled)
      if enabledMarkings.isEmpty then
        t.name -> LivenessLevel.L0
      else
        val backReach = backwardReachable(enabledMarkings, ss)
        if backReach == ss.reachableMarkings then
          t.name -> LivenessLevel.L4
        else
          t.name -> LivenessLevel.L1
    }.toMap

    LivenessResult(
      transitionLevels = levels,
      isLive = levels.values.forall(_ == LivenessLevel.L4),
      deadTransitions = levels.filter(_._2 == LivenessLevel.L0).keySet
    )

  // === Report ===

  def report(net: PetriNet, result: InvariantReport): String =
    val sb = new StringBuilder
    sb.append("===========================================================\n")
    sb.append("  ANALYSE STRUCTURELLE DU RESEAU DE PETRI\n")
    sb.append("===========================================================\n\n")

    // Incidence matrix
    sb.append("-- Matrice d'incidence C[place, transition] --\n\n")
    sb.append(f"${""}%-18s")
    net.transitions.foreach(t => sb.append(f"T${t.id}%-6s"))
    sb.append("\n")
    for p <- 0 until net.numPlaces do
      sb.append(f"  ${net.placeName(p)}%-16s")
      for t <- 0 until net.numTransitions do
        val v = result.incidenceMatrix(p)(t)
        val s = if v > 0 then s"+$v" else if v == 0 then " ." else s"$v"
        sb.append(f"$s%-6s")
      sb.append("\n")
    sb.append("\n")

    // P-invariants
    sb.append(s"-- P-invariants (${result.pInvariants.size} trouves) --\n")
    result.pInvariants.zipWithIndex.foreach { (inv, idx) =>
      val components = inv.zipWithIndex.filter(_._1 != 0).map { (coeff, p) =>
        if coeff == 1 then net.placeName(p) else s"$coeff*${net.placeName(p)}"
      }.mkString(" + ")
      val value = inv.zip(net.initialMarking.tokens).map(_ * _).sum
      sb.append(s"  y${idx + 1} : $components = $value (constante)\n")
    }
    if result.pInvariants.isEmpty then sb.append("  Aucun P-invariant non-negatif trouve\n")
    sb.append("\n")

    // T-invariants
    sb.append(s"-- T-invariants (${result.tInvariants.size} trouves) --\n")
    result.tInvariants.zipWithIndex.foreach { (inv, idx) =>
      val components = inv.zipWithIndex.filter(_._1 != 0).map { (coeff, t) =>
        if coeff == 1 then net.transitions(t).name else s"$coeff*${net.transitions(t).name}"
      }.mkString(" + ")
      sb.append(s"  x${idx + 1} : {$components}\n")
    }
    if result.tInvariants.isEmpty then sb.append("  Aucun T-invariant non-negatif trouve\n")
    sb.append("\n")

    // Boundedness
    val b = result.boundedness
    sb.append("-- Bornitude --\n")
    sb.append(s"  Le reseau est ${b.k}-borne")
    if b.isSafe then sb.append(" (sauf / safe net)")
    sb.append("\n")
    val sortedBounds = b.placeBounds.toVector.sortBy { (name, _) =>
      val idx = net.places.indexOf(name)
      if idx >= 0 then idx else Int.MaxValue
    }
    sortedBounds.foreach { (name, bound) =>
      sb.append(f"    $name%-20s : max $bound%d jeton(s)\n")
    }
    sb.append("\n")

    // Liveness
    val l = result.liveness
    sb.append("-- Vivacite --\n")
    if l.isLive then
      sb.append("  Le reseau est VIVANT (toutes les transitions sont L4)\n")
    else
      sb.append("  Le reseau n'est PAS vivant\n")
      if l.deadTransitions.nonEmpty then
        sb.append(s"  Transitions mortes : ${l.deadTransitions.mkString(", ")}\n")
    val sortedLiveness = l.transitionLevels.toVector.sortBy { (name, _) =>
      net.transitions.indexWhere(_.name == name) match
        case -1 => Int.MaxValue
        case i  => i
    }
    sortedLiveness.foreach { (name, level) =>
      sb.append(f"    $name%-22s : ${level.code}%-3s (${level.description})\n")
    }
    sb.append("\n")
    sb.append("===========================================================\n")
    sb.toString

  // === Private: Linear Algebra ===

  private def transpose(m: Vector[Vector[Int]]): Vector[Vector[Int]] =
    if m.isEmpty || m.head.isEmpty then Vector.empty
    else (0 until m.head.size).map(j => m.map(_(j))).toVector

  private def integerNullSpace(matrix: Vector[Vector[Int]]): Vector[Vector[Int]] =
    val m = matrix.size
    if m == 0 then return Vector.empty
    val n = matrix.head.size
    if n == 0 then return Vector.empty

    val mat = Array.ofDim[Long](m, n)
    for (i <- 0 until m; j <- 0 until n) mat(i)(j) = matrix(i)(j).toLong

    val pivotCols = mutable.ArrayBuffer[Int]()
    var pivotRow = 0
    val usedCols = Array.fill(n)(false)

    for col <- 0 until n if pivotRow < m do
      // Find best pivot (smallest nonzero abs value)
      var bestRow = -1
      var bestVal = Long.MaxValue
      for row <- pivotRow until m do
        val v = math.abs(mat(row)(col))
        if v > 0 && v < bestVal then
          bestVal = v
          bestRow = row

      if bestRow >= 0 then
        if bestRow != pivotRow then
          val tmp = mat(bestRow)
          mat(bestRow) = mat(pivotRow)
          mat(pivotRow) = tmp

        pivotCols += col
        usedCols(col) = true

        // Eliminate column in all other rows (full reduction)
        for row <- 0 until m if row != pivotRow do
          if mat(row)(col) != 0 then
            val a = mat(row)(col)
            val b = mat(pivotRow)(col)
            for j <- 0 until n do
              mat(row)(j) = mat(row)(j) * b - a * mat(pivotRow)(j)
            normalizeRow(mat(row))

        pivotRow += 1

    // Free columns (not used as pivots)
    val freeCols = (0 until n).filterNot(usedCols(_)).toVector

    // Build null space basis: one vector per free column
    freeCols.map { freeCol =>
      val x = Array.fill[Long](n)(0L)
      val pivotValues = pivotCols.indices.map(i => math.abs(mat(i)(pivotCols(i))))
      val lcm =
        if pivotValues.isEmpty then 1L
        else pivotValues.foldLeft(1L)((acc, v) => lcmLong(acc, v))

      x(freeCol) = lcm

      for i <- pivotCols.indices do
        val pivotVal = mat(i)(pivotCols(i))
        x(pivotCols(i)) = -mat(i)(freeCol) * (lcm / pivotVal)

      val g = gcdArray(x)
      if g > 1 then for j <- 0 until n do x(j) /= g

      x.map(_.toInt).toVector
    }

  private def findNonNegativeCombinations(basis: Vector[Vector[Int]]): Vector[Vector[Int]] =
    if basis.isEmpty then return Vector.empty
    val results = mutable.LinkedHashSet[Vector[Int]]()

    // Single vectors and their negations
    for v <- basis do
      addIfValid(results, v)
      addIfValid(results, v.map(-_))

    // Pairwise combinations with minimal coefficients
    if basis.size >= 2 then
      for i <- basis.indices; j <- basis.indices if i < j do
        for a <- -1 to 1; b <- -1 to 1 if !(a == 0 && b == 0) do
          val candidate = basis(i).zip(basis(j)).map((x, y) => a * x + b * y)
          addIfValid(results, candidate)

    results.toVector

  private def addIfValid(set: mutable.LinkedHashSet[Vector[Int]], v: Vector[Int]): Unit =
    if v.forall(_ >= 0) && v.exists(_ > 0) then
      val g = v.filter(_ != 0).map(math.abs).reduce(gcdInt)
      val normalized = v.map(_ / g)
      set += normalized

  private def normalizeRow(row: Array[Long]): Unit =
    val g = gcdArray(row)
    if g > 1 then for j <- row.indices do row(j) /= g

  private def gcdArray(arr: Array[Long]): Long =
    arr.filter(_ != 0).map(math.abs).foldLeft(0L)(gcd)

  private def gcd(a: Long, b: Long): Long =
    if b == 0 then math.abs(a) else gcd(b, a % b)

  private def gcdInt(a: Int, b: Int): Int =
    if b == 0 then math.abs(a) else gcdInt(b, a % b)

  private def lcmLong(a: Long, b: Long): Long =
    if a == 0 || b == 0 then 1L
    else math.abs(a / gcd(a, b) * b)

  private def backwardReachable(targets: Set[Marking], ss: StateSpaceResult): Set[Marking] =
    val visited = mutable.Set.from(targets)
    val queue = mutable.Queue.from(targets)
    val preds = ss.predecessorMap

    while queue.nonEmpty do
      val current = queue.dequeue()
      for prev <- preds.getOrElse(current, Vector.empty) do
        if !visited.contains(prev) then
          visited.add(prev)
          queue.enqueue(prev)

    visited.toSet
