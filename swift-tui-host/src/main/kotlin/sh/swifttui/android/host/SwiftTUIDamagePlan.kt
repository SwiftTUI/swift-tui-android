package sh.swifttui.android.host

/**
 * Pure damage planner: decides whether a frame needs a full grid repaint or can
 * be patched row-by-row against the retained bitmap. Kept Android-free so the
 * branching rules are unit-tested directly.
 *
 * Incremental repaint is only safe when this frame's damage is relative to the
 * frame we actually rendered last. Because the client polls the *latest* frame
 * (and may skip intermediate sequences whose damage would be lost), a partial
 * repaint is allowed only when `frame.sequence == previousSequence + 1`.
 */
object SwiftTUIDamagePlan {
  data class RowDamage(
    val row: Int,
    val columnRanges: List<IntRange>
  ) {
    /** Whether any damaged column range overlaps the half-open cell span
     *  `[startColumn, endColumnExclusive)`. */
    fun intersects(startColumn: Int, endColumnExclusive: Int): Boolean =
      columnRanges.any { startColumn <= it.last && it.first < endColumnExclusive }
  }

  data class Plan(
    val fullRepaint: Boolean,
    val rows: List<RowDamage>
  )

  private val FULL = Plan(fullRepaint = true, rows = emptyList())

  fun plan(
    frame: SwiftTUIFrame,
    previousSequence: Long,
    sizeChanged: Boolean
  ): Plan {
    val requiresFull =
      sizeChanged ||
        previousSequence < 0L ||
        frame.consumedGeneration != previousSequence + 1 ||
        frame.requiresFullTextRepaint ||
        frame.requiresFullGraphicsReplay ||
        frame.cells.isEmpty() ||
        // Images are composited over cells; repaint everything when present so a
        // patched cell never paints over an image.
        frame.imageAttachments.isNotEmpty()
    if (requiresFull) {
      return FULL
    }

    if (frame.gridWidth <= 0 || frame.gridHeight <= 0) {
      return Plan(fullRepaint = false, rows = emptyList())
    }
    val fullWidthRange = 0 until frame.gridWidth
    val byRow = LinkedHashMap<Int, MutableList<IntRange>>()
    for (textRow in frame.textDamageRows) {
      if (textRow.row !in 0 until frame.gridHeight) continue
      val ranges = byRow.getOrPut(textRow.row) { mutableListOf() }
      if (textRow.columnRanges.isEmpty()) {
        ranges.add(fullWidthRange)
      }
      for (range in textRow.columnRanges) {
        // SwiftTUIRange mirrors a Swift Range<Int> (upperBound exclusive).
        val lower = range.lowerBound.coerceIn(0, frame.gridWidth)
        val upper = range.upperBound.coerceIn(0, frame.gridWidth)
        if (upper > lower) {
          ranges.add(lower until upper)
        }
      }
    }
    // The decoder's dirtyRows is a summary of detailed damage, not an
    // additional full-row invalidation. Only summary-only rows use full width.
    for (row in frame.dirtyRows) {
      if (row in 0 until frame.gridHeight && row !in byRow) {
        byRow[row] = mutableListOf(fullWidthRange)
      }
    }

    val rows = byRow.mapNotNull { (row, ranges) ->
      val merged = mutableListOf<IntRange>()
      for (range in ranges.sortedBy { it.first }) {
        val previous = merged.lastOrNull()
        if (previous != null && range.first <= previous.last + 1) {
          merged[merged.lastIndex] = previous.first..maxOf(previous.last, range.last)
        } else {
          merged.add(range)
        }
      }
      if (merged.isEmpty()) null else RowDamage(row, merged)
    }
    return Plan(fullRepaint = false, rows = rows)
  }

  /** Visits only indexed damaged rows; returns the number of cells examined. */
  internal fun forEachDamagedCell(
    frame: SwiftTUIFrame,
    plan: Plan,
    action: (SwiftTUICell) -> Unit
  ): Int {
    var visited = 0
    for (row in plan.rows) {
      for (cell in frame.cellsByRow[row.row].orEmpty()) {
        visited += 1
        if (!cell.isContinuation &&
          row.intersects(cell.x, cell.x + cell.spanWidth.coerceAtLeast(1))
        ) {
          action(cell)
        }
      }
    }
    return visited
  }
}
