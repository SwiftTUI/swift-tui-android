package sh.swifttui.android.host

/**
 * How [SwiftTUIWebSurfaceSession] turns its retained wire rows into a frame's
 * cells. Both modes publish equal frames. The switch is internal: it keeps the
 * earlier path selectable, and testable against, while retained decoded rows
 * are adopted (STUI-300).
 */
internal enum class SwiftTUIRowDecoding(val token: String) {
  /** Allocate every cell and reapply every link for every frame. */
  Rebuild("rebuild"),

  /** Reuse the decoded rows a record did not touch; publish them as a [SwiftTUIRowCells] view. */
  RetainedRows("retained-rows");

  companion object {
    /** Selects the mode of every session created without an explicit one. */
    const val PROPERTY = "sh.swifttui.android.host.rowDecoding"

    val Default = RetainedRows

    fun current(): SwiftTUIRowDecoding {
      val token = System.getProperty(PROPERTY) ?: return Default
      return requireNotNull(SwiftTUIRowDecoding.entries.firstOrNull { it.token == token }) {
        "unknown $PROPERTY value $token"
      }
    }
  }
}

/** One decoded row's cells in column order. Never mutated after construction. */
internal class SwiftTUIDecodedRow(
  private val cells: Array<SwiftTUICell>
) : AbstractList<SwiftTUICell>(), RandomAccess {
  override val size: Int get() = cells.size

  override fun get(index: Int): SwiftTUICell = cells[index]
}

/**
 * A frame's cells as the concatenation of its decoded rows, without copying
 * them into one list. Rows are shared with the decoder session and with every
 * later frame a delta did not replace them in, so neither this list nor a row
 * is ever mutated: a published frame keeps the cells it was published with.
 *
 * Every cell of `rows[y]` has `y` as its row, which is what lets [byRow]
 * answer the frame's row index without grouping the cells again.
 */
internal class SwiftTUIRowCells(
  private val rows: Array<SwiftTUIDecodedRow>
) : AbstractList<SwiftTUICell>(), RandomAccess {
  /** `offsets[y]` cells precede row `y`; the last entry is the cell count. */
  private val offsets = IntArray(rows.size + 1)

  init {
    var total = 0
    for (row in rows.indices) {
      offsets[row] = total
      total += rows[row].size
    }
    offsets[rows.size] = total
  }

  override val size: Int get() = offsets[rows.size]

  override fun get(index: Int): SwiftTUICell {
    if (index < 0 || index >= size) {
      throw IndexOutOfBoundsException("index: $index, size: $size")
    }
    // The last row that starts at or before the index is the one that holds it:
    // an empty row shares its offset with the row after it.
    var low = 0
    var high = rows.size - 1
    while (low < high) {
      val middle = (low + high + 1) ushr 1
      if (offsets[middle] <= index) low = middle else high = middle - 1
    }
    return rows[low][index - offsets[low]]
  }

  override fun iterator(): Iterator<SwiftTUICell> = object : Iterator<SwiftTUICell> {
    private var row = 0
    private var column = 0

    override fun hasNext(): Boolean {
      while (row < rows.size && column >= rows[row].size) {
        row += 1
        column = 0
      }
      return row < rows.size
    }

    override fun next(): SwiftTUICell {
      if (!hasNext()) throw NoSuchElementException()
      return rows[row][column++]
    }
  }

  /** The rows that hold a cell, keyed by row, as `groupBy { it.y }` would yield them. */
  fun byRow(): Map<Int, List<SwiftTUICell>> = RowIndex()

  private inner class RowIndex : AbstractMap<Int, List<SwiftTUICell>>() {
    override val size: Int get() = rows.count { it.isNotEmpty() }

    override fun containsKey(key: Int): Boolean = get(key) != null

    override fun get(key: Int): List<SwiftTUICell>? =
      if (key >= 0 && key < rows.size && rows[key].isNotEmpty()) rows[key] else null

    override val entries: Set<Map.Entry<Int, List<SwiftTUICell>>> by lazy {
      val occupied = LinkedHashSet<Map.Entry<Int, List<SwiftTUICell>>>()
      for (row in rows.indices) {
        if (rows[row].isNotEmpty()) {
          occupied.add(java.util.AbstractMap.SimpleImmutableEntry<Int, List<SwiftTUICell>>(row, rows[row]))
        }
      }
      occupied
    }
  }
}
