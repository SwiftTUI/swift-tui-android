package sh.swifttui.android.host

/** Cell-space clip commands consumed by the native Canvas painter. */
internal object SwiftTUIRasterClipGeometry {
  fun damage(plan: SwiftTUIDamagePlan.Plan): List<SwiftTUIRect> = buildList {
    for (row in plan.rows) {
      for (range in row.columnRanges) {
        if (!range.isEmpty()) {
          add(SwiftTUIRect(range.first, row.row, range.last - range.first + 1, 1))
        }
      }
    }
  }

  fun cell(cell: SwiftTUICell): SwiftTUIRect =
    SwiftTUIRect(cell.x, cell.y, cell.spanWidth.coerceAtLeast(1), 1)

  fun pixelEnd(origin: Int, extent: Int, cellSize: Float): Float =
    // Sum in a wider integer before pixel conversion. Besides preventing Int
    // wraparound, this retains cancellation for large opposite-sign values.
    ((origin.toLong() + extent.toLong()).toDouble() * cellSize).toFloat()
}
