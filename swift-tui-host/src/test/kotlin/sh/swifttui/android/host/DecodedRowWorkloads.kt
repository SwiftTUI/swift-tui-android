package sh.swifttui.android.host

import java.security.MessageDigest

/**
 * Deterministic web-surface record streams for measuring the decode →
 * publication → row-index path on two fixed grids (80×24 and 200×60).
 *
 * Every record has the shape the Swift encoder emits for the Android host: a
 * stamped full frame, then stamped deltas in the negotiated `stylesBase`
 * append shape whose `deltaRows` replace whole rows. Records are built as
 * text, without a JSON library, so the JVM and ART see byte-identical input;
 * [corpusSHA256] pins those bytes.
 *
 * A stream is one keyframe followed by a closed cycle of steps: applying every
 * step once returns the screen to the keyframe's content, so the cycle can be
 * replayed for any number of operations. The three stamps a record carries
 * (`gen`, `baselineGen`, `sequence`) are seven-digit numbers at known byte
 * offsets, rewritten in place by [DecodedRowStream] so replayed deltas stay
 * contiguous without allocating.
 */
internal class DecodedRowWorkload(
  val name: String,
  val width: Int,
  val height: Int,
  val keyframe: StampedRecord,
  val steps: List<StampedRecord>
) {
  val id: String get() = "$name-${width}x$height"
}

/** One record's bytes plus where its stamps and declared change counts live. */
internal class StampedRecord(
  val bytes: ByteArray,
  private val generationOffset: Int,
  private val baselineGenerationOffset: Int,
  private val sequenceOffset: Int,
  /** Rows the record replaces (the grid height for a full frame). */
  val replacedRows: Int,
  /** Lead cells whose text, span or style differ from the preceding screen. */
  val changedCells: Int
) {
  fun stamp(generation: Int) {
    require(generation in FIRST_STAMP..LAST_STAMP)
    write(generationOffset, generation)
    write(sequenceOffset, generation)
    if (baselineGenerationOffset >= 0) write(baselineGenerationOffset, generation - 1)
  }

  private fun write(offset: Int, value: Int) {
    var remaining = value
    for (index in STAMP_DIGITS - 1 downTo 0) {
      bytes[offset + index] = ('0'.code + remaining % 10).toByte()
      remaining /= 10
    }
  }

  companion object {
    const val STAMP_DIGITS = 7
    const val FIRST_STAMP = 1_000_000
    const val LAST_STAMP = 9_999_999
  }
}

/** Replays one workload: the keyframe once, then its steps cyclically. */
internal class DecodedRowStream(private val workload: DecodedRowWorkload) {
  private var generation = StampedRecord.FIRST_STAMP
  private var cursor = 0

  fun keyframe(): ByteArray {
    generation = StampedRecord.FIRST_STAMP
    cursor = 0
    workload.keyframe.stamp(generation)
    return workload.keyframe.bytes
  }

  fun next(): ByteArray {
    val record = workload.steps[cursor]
    cursor = if (cursor + 1 == workload.steps.size) 0 else cursor + 1
    generation += 1
    record.stamp(generation)
    return record.bytes
  }
}

/** A wire cell without its column: the column is the running span sum. */
private data class WorkloadCell(val text: String, val span: Int, val style: Int)

private typealias Screen = List<List<WorkloadCell>>

internal object DecodedRowWorkloads {
  val names = listOf(
    "one-cell", "one-row", "scroll", "all-rows", "full-keyframe", "image-one-cell", "linked-one-cell"
  )
  val grids = listOf(80 to 24, 200 to 60)

  fun build(name: String, width: Int, height: Int): DecodedRowWorkload {
    require((width to height) in grids) { "unsupported grid ${width}x$height" }
    val base = baseScreen(width, height)
    val changed = base.map(::changedRow)
    val extras = when (name) {
      "image-one-cell" -> Extras(image = true)
      "linked-one-cell" -> Extras(links = true)
      else -> Extras()
    }
    val screens: List<Screen> = when (name) {
      "one-cell", "image-one-cell", "linked-one-cell" -> {
        // Each position toggles twice, so the cycle closes on the keyframe.
        val positions = singleCellPositions(base, width, height)
        var screen = base
        buildList {
          repeat(2) {
            for ((row, index) in positions) {
              screen = screen.toggledCell(row, index, base)
              add(screen)
            }
          }
        }
      }
      "one-row" -> {
        val order = shuffledRows(height)
        var screen = base
        buildList {
          repeat(2) {
            for (row in order) {
              screen = screen.withRow(row, if (screen[row] === base[row]) changed[row] else base[row])
              add(screen)
            }
          }
        }
      }
      "scroll" -> {
        // Rows 1..H-2 scroll one document line per step; header and footer stay.
        // The last step returns to offset zero, which is the keyframe.
        val document = List(SCROLL_DOCUMENT_LINES) { line(41_000 + it * 53 + width, width, it % 7 == 3) }
        List(SCROLL_DOCUMENT_LINES) { step ->
          val offset = (step + 1) % SCROLL_DOCUMENT_LINES
          List(height) { y ->
            if (y == 0 || y == height - 1) base[y] else document[(offset + y - 1) % SCROLL_DOCUMENT_LINES]
          }
        }
      }
      "all-rows", "full-keyframe" -> listOf(changed, base)
      else -> throw IllegalArgumentException("unknown workload $name")
    }
    val first = if (name == "scroll") screens.last() else base
    check(screens.last() == first) { "$name: the step cycle must close on the keyframe" }
    val keyframe = fullRecord(first, width, height, extras, scrollOffset = 0, changedCells = cellCount(first))
    var previous = first
    val steps = screens.mapIndexed { index, screen ->
      val record = if (name == "full-keyframe") {
        fullRecord(screen, width, height, extras, scrollOffset = 0, changedCells = changedCells(previous, screen, width))
      } else {
        deltaRecord(previous, screen, width, height, extras, scrollOffset = if (name == "scroll") (index + 1) % SCROLL_DOCUMENT_LINES else 0)
      }
      previous = screen
      record
    }
    return DecodedRowWorkload(name, width, height, keyframe, steps)
  }

  /** SHA-256 over every workload's records at their initial stamps. */
  fun corpusSHA256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for ((width, height) in grids) {
      for (name in names) {
        val workload = build(name, width, height)
        digest.update(workload.id.encodeToByteArray())
        digest.update(workload.keyframe.bytes)
        workload.steps.forEach { digest.update(it.bytes) }
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  // --- Screen model -------------------------------------------------------

  private class Extras(val image: Boolean = false, val links: Boolean = false)

  private const val SEED = 300
  private const val SCROLL_DOCUMENT_LINES = 64
  private const val ONE_CELL_POSITIONS = 32
  private const val LINK_TARGETS = 8
  private const val IMAGE_PAYLOAD =
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAHUlEQVR4AQESAO3/AP8AAP//AAD/AP8AAP//AAD/R8oH+YOsvdsAAAAASUVORK5CYII="

  private val styleTable = listOf(
    "null",
    """{"fg":"#ECEFF4FF"}""",
    """{"fg":"#88C0D0FF","em":1}""",
    """{"fg":"#A3BE8CFF","bg":"#2E3440FF"}""",
    """{"fg":"#EBCB8BFF","em":2}""",
    """{"fg":"#BF616AFF","em":1,"underline":{"pattern":"solid","color":"#BF616AFF"}}""",
    """{"fg":"#B48EADFF","opacity":0.75}""",
    """{"fg":"#D8DEE9FF","bg":"#3B4252FF","em":16}""",
    """{"fg":"#8FBCBBFF","em":4}""",
    """{"fg":"#5E81ACFF","strikethrough":{"pattern":"solid","color":"#5E81ACFF"}}""",
    """{"fg":"#D08770FF","em":3}""",
    """{"fg":"#81A1C1FF","bg":"#434C5EFF"}""",
    """{"fg":"#E5E9F0FF","em":1,"opacity":0.5}"""
  )

  private val words = listOf(
    "status", "build", "render", "frame", "delta", "queue", "commit", "layout", "focus",
    "scroll", "index", "cursor", "signal", "timer", "buffer", "stream", "socket", "thread",
    "memory", "widget", "column", "header", "footer", "margin", "accent", "shadow"
  )
  private val wideGlyphs = listOf("宽", "字", "表", "示", "界", "面")

  private class Generator(seed: Int) {
    private var state = seed.toLong()
    fun next(bound: Int): Int {
      state = state * 6364136223846793005L + 1442695040888963407L
      return ((state ushr 33) % bound).toInt()
    }
  }

  private fun baseScreen(width: Int, height: Int): Screen =
    List(height) { y -> line(SEED * 1_000 + y * 31 + width, width, y % 7 == 3) }

  /** Styled words separated by unstyled gaps, covering exactly [width] columns. */
  private fun line(seed: Int, width: Int, withWideGlyphs: Boolean): List<WorkloadCell> {
    val random = Generator(seed)
    val cells = ArrayList<WorkloadCell>(width)
    var column = 0
    while (column < width) {
      val style = 1 + random.next(styleTable.size - 1)
      if (withWideGlyphs && random.next(5) == 0 && column + 2 <= width) {
        cells.add(WorkloadCell(wideGlyphs[random.next(wideGlyphs.size)], 2, style))
        column += 2
      } else {
        for (character in words[random.next(words.size)]) {
          if (column == width) break
          cells.add(WorkloadCell(character.toString(), 1, style))
          column += 1
        }
      }
      repeat(1 + random.next(3)) {
        if (column < width) {
          cells.add(WorkloadCell(" ", 1, 0))
          column += 1
        }
      }
    }
    return cells
  }

  /** The same row with every cell's text replaced, so every cell changes. */
  private fun changedRow(row: List<WorkloadCell>): List<WorkloadCell> = row.map { it.copy(text = changedText(it.text)) }

  private fun changedText(text: String): String {
    val wideIndex = wideGlyphs.indexOf(text)
    if (wideIndex >= 0) return wideGlyphs[(wideIndex + 1) % wideGlyphs.size]
    val character = text[0]
    return when {
      character in 'a'..'y' -> (character + 1).toString()
      character == 'z' -> "a"
      character == ' ' -> "."
      else -> "#"
    }
  }

  private fun singleCellPositions(base: Screen, width: Int, height: Int): List<Pair<Int, Int>> {
    val random = Generator(SEED + width * 7 + height)
    val positions = LinkedHashSet<Pair<Int, Int>>()
    while (positions.size < ONE_CELL_POSITIONS) {
      val row = random.next(height)
      val index = random.next(base[row].size)
      if (base[row][index].span == 1) positions.add(row to index)
    }
    return positions.toList()
  }

  private fun shuffledRows(height: Int): List<Int> {
    val random = Generator(SEED + height)
    val rows = (0 until height).toMutableList()
    for (index in rows.lastIndex downTo 1) {
      val other = random.next(index + 1)
      val swapped = rows[index]
      rows[index] = rows[other]
      rows[other] = swapped
    }
    return rows
  }

  private fun Screen.withRow(row: Int, cells: List<WorkloadCell>): Screen =
    toMutableList().also { it[row] = cells }

  private fun Screen.toggledCell(row: Int, index: Int, base: Screen): Screen {
    val original = base[row][index]
    val current = this[row][index]
    val next = if (current == original) original.copy(text = changedText(original.text)) else original
    return withRow(row, this[row].toMutableList().also { it[index] = next })
  }

  private fun cellCount(screen: Screen): Int = screen.sumOf { it.size }

  /** Lead cells of [next] that differ from the lead cell at the same column of [previous]. */
  private fun changedCells(previous: Screen, next: Screen, width: Int): Int =
    next.indices.sumOf { changedColumns(previous[it], next[it], width).first }

  private fun changedColumns(previous: List<WorkloadCell>, next: List<WorkloadCell>, width: Int): Pair<Int, List<IntRange>> {
    if (previous == next) return 0 to emptyList()
    val before = arrayOfNulls<WorkloadCell>(width)
    var column = 0
    for (cell in previous) {
      before[column] = cell
      column += cell.span
    }
    var changed = 0
    val damaged = BooleanArray(width)
    column = 0
    for (cell in next) {
      if (before[column] != cell) {
        changed += 1
        for (covered in column until column + cell.span) damaged[covered] = true
      }
      column += cell.span
    }
    val ranges = ArrayList<IntRange>()
    var start = -1
    for (index in 0..width) {
      val isDamaged = index < width && damaged[index]
      if (isDamaged && start < 0) start = index
      if (!isDamaged && start >= 0) {
        ranges.add(start until index)
        start = -1
      }
    }
    return changed to ranges
  }

  // --- Record text --------------------------------------------------------

  private class Header(val text: StringBuilder, val generation: Int, val baselineGeneration: Int, val sequence: Int)

  private fun header(version: Int, delta: Boolean, width: Int, height: Int): Header {
    val text = StringBuilder(SwiftTUIWebSurfaceSession.RECORD_PREFIX)
    text.append("{\"version\":").append(version)
    if (delta) text.append(",\"encoding\":\"delta\"")
    text.append(",\"epoch\":").append(SEED).append(",\"gen\":")
    val generation = text.length
    text.append(StampedRecord.FIRST_STAMP)
    var baselineGeneration = -1
    if (delta) {
      text.append(",\"baselineGen\":")
      baselineGeneration = text.length
      text.append(StampedRecord.FIRST_STAMP)
    }
    text.append(",\"sequence\":")
    val sequence = text.length
    text.append(StampedRecord.FIRST_STAMP)
    text.append(",\"width\":").append(width).append(",\"height\":").append(height)
    // Every character so far is ASCII, so these are byte offsets too.
    return Header(text, generation, baselineGeneration, sequence)
  }

  private fun fullRecord(
    screen: Screen,
    width: Int,
    height: Int,
    extras: Extras,
    scrollOffset: Int,
    changedCells: Int
  ): StampedRecord {
    val header = header(version = 2, delta = false, width = width, height = height)
    val text = header.text
    text.append(",\"styles\":[").append(styleTable.joinToString(",")).append("],\"rows\":[")
    screen.forEachIndexed { y, row ->
      if (y > 0) text.append(',')
      appendRow(text, row)
    }
    text.append("],\"images\":[")
    if (extras.image) appendImage(text, width, height, withPayload = true)
    text.append("],\"damage\":{\"textRows\":[],\"requiresFullTextRepaint\":true,")
    text.append("\"requiresFullGraphicsReplay\":true}")
    appendTrailer(text, width, height, extras, scrollOffset)
    return StampedRecord(
      text.toString().encodeToByteArray(), header.generation, header.baselineGeneration,
      header.sequence, replacedRows = height, changedCells = changedCells
    )
  }

  private fun deltaRecord(
    previous: Screen,
    next: Screen,
    width: Int,
    height: Int,
    extras: Extras,
    scrollOffset: Int
  ): StampedRecord {
    val header = header(version = 3, delta = true, width = width, height = height)
    val text = header.text
    // The negotiated append shape: this record adds no style.
    text.append(",\"stylesBase\":").append(styleTable.size).append(",\"styles\":[],\"deltaRows\":[")
    val damage = StringBuilder()
    var replacedRows = 0
    var changedCells = 0
    for (y in next.indices) {
      val (changed, ranges) = changedColumns(previous[y], next[y], width)
      if (changed == 0) continue
      if (replacedRows > 0) {
        text.append(',')
        damage.append(',')
      }
      text.append('[').append(y).append(',')
      appendRow(text, next[y])
      text.append(']')
      damage.append('[').append(y).append(",[")
      ranges.forEachIndexed { index, range ->
        if (index > 0) damage.append(',')
        damage.append('[').append(range.first).append(',').append(range.last + 1).append(']')
      }
      damage.append("]]")
      replacedRows += 1
      changedCells += changed
    }
    text.append("],\"images\":[")
    // Transmit-once: the keyframe delivered the payload.
    if (extras.image) appendImage(text, width, height, withPayload = false)
    text.append("],\"damage\":{\"textRows\":[").append(damage)
    text.append("],\"requiresFullTextRepaint\":false,\"requiresFullGraphicsReplay\":false}")
    appendTrailer(text, width, height, extras, scrollOffset)
    return StampedRecord(
      text.toString().encodeToByteArray(), header.generation, header.baselineGeneration,
      header.sequence, replacedRows = replacedRows, changedCells = changedCells
    )
  }

  private fun appendRow(text: StringBuilder, row: List<WorkloadCell>) {
    text.append('[')
    var column = 0
    row.forEachIndexed { index, cell ->
      if (index > 0) text.append(',')
      text.append('[').append(column).append(",\"").append(cell.text).append("\",")
      text.append(cell.span).append(',').append(cell.style).append(']')
      column += cell.span
    }
    text.append(']')
  }

  private fun appendImage(text: StringBuilder, width: Int, height: Int, withPayload: Boolean) {
    val bounds = "[${width / 2},2,${width / 4},${height / 3}]"
    text.append("{\"id\":\"png:5f3759df00000300:86\",\"format\":\"png\",\"bounds\":").append(bounds)
    text.append(",\"visibleBounds\":").append(bounds)
    text.append(",\"scalingMode\":\"fit\",\"opacity\":1.0,\"pixelSize\":[2,2]")
    if (withPayload) text.append(",\"dataBase64\":\"").append(IMAGE_PAYLOAD).append('"')
    text.append('}')
  }

  /** The per-frame fields the encoder repeats on every record, full or delta. */
  private fun appendTrailer(text: StringBuilder, width: Int, height: Int, extras: Extras, scrollOffset: Int) {
    text.append(",\"accessibilityTree\":[{\"id\":\"root\",\"rect\":[0,0,").append(width).append(',')
    text.append(height).append("],\"role\":\"group\"},{\"id\":\"root/list\",\"parentId\":\"root\",")
    text.append("\"rect\":[0,1,").append(width).append(',').append(height - 2)
    text.append("],\"role\":\"list\",\"label\":\"Items\"}")
    for (item in 0 until height * 2 / 3) {
      text.append(",{\"id\":\"root/list/item-").append(item).append("\",\"parentId\":\"root/list\",")
      text.append("\"rect\":[2,").append(1 + item).append(',').append(width - 4).append(",1],")
      text.append("\"role\":\"button\",\"label\":\"Item ").append(item).append("\",")
      text.append("\"isFocused\":").append(item == 3).append(",\"actionTarget\":\"target-").append(item)
      text.append("\",\"actions\":[\"activate\",\"focus\"],\"isEnabled\":true}")
    }
    text.append("],\"scrollRegions\":[{\"id\":\"root/list\",\"rect\":[0,1,").append(width).append(',')
    text.append(height - 2).append("],\"offset\":[0,").append(scrollOffset).append("],\"content\":[")
    text.append(width).append(',').append(SCROLL_DOCUMENT_LINES + height - 2).append("]}]")
    if (extras.links) {
      text.append(",\"links\":[")
      var first = true
      for (y in 1 until height step 3) {
        if (!first) text.append(',')
        first = false
        text.append('[').append(y).append(",[[2,").append(minOf(width - 4, 40)).append(',')
        text.append(y / 3 % LINK_TARGETS).append("]]]")
      }
      text.append("],\"linkTargets\":[")
      for (target in 0 until LINK_TARGETS) {
        if (target > 0) text.append(',')
        text.append("\"https://example.com/docs/section-").append(target).append('"')
      }
      text.append(']')
    }
    text.append(",\"focusPresentation\":{\"focusedIdentity\":\"root/list/item-3\",")
    text.append("\"semantics\":\"activate\",\"prefersTextInput\":false,\"hasFocusedRegion\":true}")
    text.append(",\"terminalStyle\":{\"foregroundColor\":{\"hex\":\"#ECEFF4FF\"},")
    text.append("\"backgroundColor\":{\"hex\":\"#1E222AFF\"},\"tintColor\":{\"hex\":\"#56B6C2FF\"}}}\n")
  }
}
