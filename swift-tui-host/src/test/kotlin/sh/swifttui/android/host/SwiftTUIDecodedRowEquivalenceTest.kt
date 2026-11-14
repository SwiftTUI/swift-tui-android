package sh.swifttui.android.host

import java.lang.ref.WeakReference
import java.util.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds identical record streams to one decoder session per
 * [SwiftTUIRowDecoding] mode and requires equal published frames at every
 * step. [SwiftTUIRowDecoding.Rebuild] is the reference: it allocates every
 * cell and reapplies every link for every frame.
 *
 * Every step also checks the two ownership rules of the retained path: a
 * frame that was published never changes afterwards, and the session retains
 * only the rows of the frame it returned last.
 */
class SwiftTUIDecodedRowEquivalenceTest {
  private val prefix = SwiftTUIWebSurfaceSession.RECORD_PREFIX

  // --- Differential harness ----------------------------------------------

  /** A frame as it was when published, kept apart from the frame's own lists. */
  private class Published(val mode: SwiftTUIRowDecoding, val frame: SwiftTUIFrame) {
    val cells = ArrayList(frame.cells)
    // The rendered text of a large grid is megabytes; its cells' identity is checked instead.
    val text = if (frame.cells.size <= 512) frame.toString() else null
    val hash = frame.hashCode()
    val index = frame.cellsByRow.entries.associate { it.key to ArrayList(it.value) }

    fun assertUnchanged() {
      val context = "$mode frame ${frame.consumedGeneration} changed after publication"
      assertEquals(context, cells.size, frame.cells.size)
      frame.cells.forEachIndexed { position, cell -> assertSame(context, cells[position], cell) }
      if (text != null) assertEquals(context, text, frame.toString())
      assertEquals(context, hash, frame.hashCode())
      assertEquals(context, index.keys.toList(), frame.cellsByRow.keys.toList())
      for ((row, rowCells) in index) {
        val current = frame.cellsByRow.getValue(row)
        assertEquals(context, rowCells.size, current.size)
        current.forEachIndexed { position, cell -> assertSame(context, rowCells[position], cell) }
      }
    }
  }

  private class Differential {
    val modes = SwiftTUIRowDecoding.entries
    val sessions = modes.map { SwiftTUIWebSurfaceSession(it) }
    private val published = ArrayList<Published>()
    private var latest = arrayOfNulls<SwiftTUIFrame>(modes.size)
    private var previous = arrayOfNulls<SwiftTUIFrame>(modes.size)
    var frames = 0
      private set
    var steps = 0
      private set

    init {
      sessions.forEachIndexed { index, session -> assertEquals(modes[index], session.rowDecoding) }
    }

    /** Decodes [record] on every path; returns the reference path's frame. */
    fun feed(record: String): SwiftTUIFrame? {
      steps += 1
      val context = "step $steps"
      val retainedBefore = sessions.map { it.retainedRowCells() }
      val outcomes = sessions.map { session -> runCatching { session.decode(record) } }
      val reference = outcomes[0]
      val expected = reference.getOrNull()
      outcomes.forEachIndexed { index, outcome ->
        val mode = "$context ${modes[index]}"
        assertEquals(mode, reference.exceptionOrNull()?.javaClass, outcome.exceptionOrNull()?.javaClass)
        assertEquals(mode, reference.exceptionOrNull()?.message, outcome.exceptionOrNull()?.message)
        assertEquals(mode, sessions[0].pendingResyncScope, sessions[index].pendingResyncScope)
        assertEquals(mode, sessions[0].lastEpoch, sessions[index].lastEpoch)
        val actual = outcome.getOrNull()
        assertEquals(mode, expected == null, actual == null)
        if (expected != null && actual != null) assertSameFrame(mode, expected, actual)
        assertRetentionBound(mode, index, actual, retainedBefore[index])
      }
      if (expected != null) {
        frames += 1
        previous = latest
        latest = Array(modes.size) { outcomes[it].getOrNull() }
        latest.forEachIndexed { index, frame -> published.add(Published(modes[index], frame!!)) }
      }
      if (steps % 16 == 0) assertPublishedFramesUnchanged()
      return expected
    }

    fun reset() {
      sessions.forEach { session ->
        session.reset()
        assertNull(session.retainedRowCells())
      }
    }

    fun requestKeyframeRecovery() = sessions.forEach { it.requestKeyframeRecovery() }

    fun assertPublishedFramesUnchanged() = published.forEach { it.assertUnchanged() }

    /**
     * Rows of the latest frame whose cell objects are the previous frame's:
     * the rows [mode] reused instead of decoding again.
     */
    fun reusedRows(mode: SwiftTUIRowDecoding): Set<Int> {
      val index = modes.indexOf(mode)
      val before = previous[index]?.cellsByRow ?: return emptySet()
      val after = latest[index]!!.cellsByRow
      return after.keys.filterTo(sortedSetOf()) { row ->
        val earlier = before[row]
        val current = after.getValue(row)
        earlier != null && earlier.size == current.size &&
          current.indices.all { earlier[it] === current[it] }
      }
    }

    fun rowsWithCells(): Set<Int> = latest[0]!!.cellsByRow.keys.toSortedSet()

    /** The session holds decoded rows for its one active baseline, and only on the retained path. */
    private fun assertRetentionBound(
      context: String,
      index: Int,
      frame: SwiftTUIFrame?,
      before: List<List<SwiftTUICell>>?
    ) {
      val retained = sessions[index].retainedRowCells()
      if (modes[index] == SwiftTUIRowDecoding.Rebuild) {
        assertNull(context, retained)
        return
      }
      if (frame == null) {
        // A refused or malformed record leaves the retained rows exactly as they were.
        assertEquals(context, before?.size, retained?.size)
        retained?.forEachIndexed { row, cells -> assertSame(context, before!![row], cells) }
        return
      }
      assertNotNull(context, retained)
      assertTrue(context, retained!!.size <= frame.gridHeight)
      val flattened = retained.flatten()
      assertEquals(context, frame.cells.size, flattened.size)
      frame.cells.forEachIndexed { position, cell -> assertSame(context, flattened[position], cell) }
    }

    private fun assertSameFrame(context: String, expected: SwiftTUIFrame, actual: SwiftTUIFrame) {
      assertEquals(context, expected, actual)
      assertEquals(context, actual, expected)
      assertEquals(context, expected.hashCode(), actual.hashCode())
      assertEquals(context, expected.toString(), actual.toString())

      // The flat list: by index, by iteration, and at its bounds.
      assertEquals(context, expected.cells.size, actual.cells.size)
      assertEquals(context, expected.cells.isEmpty(), actual.cells.isEmpty())
      for (position in expected.cells.indices) {
        assertEquals(context, expected.cells[position], actual.cells[position])
      }
      val iterated = ArrayList<SwiftTUICell>()
      for (cell in actual.cells) iterated.add(cell)
      assertEquals(context, expected.cells.toList(), iterated)
      assertThrows(IndexOutOfBoundsException::class.java) { actual.cells[actual.cells.size] }
      assertThrows(IndexOutOfBoundsException::class.java) { actual.cells[-1] }

      // The row index the painter reads.
      val expectedIndex = expected.cellsByRow
      val actualIndex = actual.cellsByRow
      assertEquals(context, expectedIndex, actualIndex)
      assertEquals(context, actualIndex, expectedIndex)
      assertEquals(context, expectedIndex.size, actualIndex.size)
      assertEquals(context, expectedIndex.keys.toList(), actualIndex.keys.toList())
      assertEquals(
        context,
        expectedIndex.entries.map { it.key to it.value },
        actualIndex.entries.map { it.key to it.value }
      )
      for (row in -1..expected.gridHeight + 1) {
        assertEquals(context, expectedIndex[row], actualIndex[row])
        assertEquals(context, expectedIndex.containsKey(row), actualIndex.containsKey(row))
      }

      // Hit-testing, on every cell of a small grid and a fixed sample of a large one.
      val columns = if (expected.gridWidth <= 16) (0..expected.gridWidth + 1).toList()
        else listOf(0, 1, 2, expected.gridWidth / 2, expected.gridWidth, expected.gridWidth + 1)
      val rows = if (expected.gridHeight <= 16) (0..expected.gridHeight + 1).toList()
        else listOf(0, 1, expected.gridHeight / 2, expected.gridHeight, expected.gridHeight + 1)
      for (row in rows) {
        for (column in columns) {
          assertEquals(context, expected.cellAt(column, row), actual.cellAt(column, row))
        }
      }

      // The painter's selection, for a contiguous frame and for a skipped one.
      for (renderedGeneration in listOf(expected.consumedGeneration - 1, -1L)) {
        val expectedPlan = SwiftTUIDamagePlan.plan(expected, renderedGeneration, sizeChanged = false)
        val actualPlan = SwiftTUIDamagePlan.plan(actual, renderedGeneration, sizeChanged = false)
        assertEquals(context, expectedPlan, actualPlan)
        if (!expectedPlan.fullRepaint) {
          val expectedCells = ArrayList<SwiftTUICell>()
          val actualCells = ArrayList<SwiftTUICell>()
          assertEquals(
            context,
            SwiftTUIDamagePlan.forEachDamagedCell(expected, expectedPlan, expectedCells::add),
            SwiftTUIDamagePlan.forEachDamagedCell(actual, actualPlan, actualCells::add)
          )
          assertEquals(context, expectedCells, actualCells)
        }
      }

      // The copy SwiftTUIHostState publishes keeps the same cells and index.
      val copy = actual.copy(accessibilityActionResponse = null)
      assertEquals(context, expected.cells, copy.cells)
      assertEquals(context, expectedIndex, copy.cellsByRow)
    }
  }

  // --- Record text ---------------------------------------------------------

  /** A row of single-width cells, one per character, all in [style]. */
  private fun row(text: String, style: Int = 0): String =
    text.mapIndexed { x, character -> """[$x,"$character",1,$style]""" }.joinToString(",", "[", "]")

  private fun full(
    gen: Int,
    width: Int,
    height: Int,
    styles: String,
    rows: List<String>,
    epoch: Int = 7,
    extra: String = ""
  ): String = prefix +
    """{"version":2,"epoch":$epoch,"gen":$gen,"sequence":$gen,"width":$width,"height":$height,""" +
    """"styles":$styles,"rows":${rows.joinToString(",", "[", "]")},"images":[]$extra}""" + "\n"

  private fun delta(
    gen: Int,
    width: Int,
    height: Int,
    styles: String,
    rows: Map<Int, String>,
    epoch: Int = 7,
    baselineGen: Int = gen - 1,
    extra: String = ""
  ): String = prefix +
    """{"version":3,"encoding":"delta","epoch":$epoch,"gen":$gen,"baselineGen":$baselineGen,""" +
    """"sequence":$gen,"width":$width,"height":$height,$styles,"deltaRows":""" +
    rows.entries.joinToString(",", "[", "]") { "[${it.key},${it.value}]" } +
    ""","damage":{"textRows":${rows.keys.joinToString(",", "[", "]") { "[$it,[]]" }},""" +
    """"requiresFullTextRepaint":false,"requiresFullGraphicsReplay":false}$extra}""" + "\n"

  private val red = """{"fg":"#FF0000FF"}"""
  private val green = """{"fg":"#00FF00FF","em":1}"""
  private val blue = """{"fg":"#0000FFFF","underline":{"pattern":"solid","color":"#0000FFFF"}}"""
  private fun Differential.assertReused(vararg rows: Int) {
    assertEquals(emptySet<Int>(), reusedRows(SwiftTUIRowDecoding.Rebuild))
    assertEquals(rows.toSortedSet(), reusedRows(SwiftTUIRowDecoding.RetainedRows))
  }

  // --- The switch ----------------------------------------------------------

  @Test
  fun aSessionCreatedWithoutAModeUsesTheProcessSwitch() {
    val selected = System.getProperty(SwiftTUIRowDecoding.PROPERTY)
    val expected = SwiftTUIRowDecoding.entries.firstOrNull { it.token == selected }
      ?: SwiftTUIRowDecoding.Default
    assertEquals(expected, SwiftTUIWebSurfaceSession().rowDecoding)
    // Recorded in the test report, so a suite run names the path it exercised.
    println("row decoding under test: ${SwiftTUIWebSurfaceSession().rowDecoding.token}")
  }

  // --- Styles --------------------------------------------------------------

  @Test
  fun styleAppendKeepsUntouchedRowsAndResolvesAppendedSlots() {
    val streams = Differential()
    streams.feed(full(1, 3, 3, "[null,$red]", listOf(row("abc", 1), row("def", 0), row("ghi", 1))))

    // Appending a style and repainting one row leaves the other two alone.
    val appended = streams.feed(
      delta(2, 3, 3, """"stylesBase":2,"styles":[$green]""", mapOf(1 to row("xyz", 2)))
    )!!
    streams.assertReused(0, 2)
    assertEquals(setOf("bold"), appended.cellAt(1, 2)?.style?.emphasis)
    assertEquals("#FF0000FF", appended.cellAt(1, 1)?.style?.foregroundColor?.hex)

    // An empty append repaints nothing but the replaced row.
    streams.feed(delta(3, 3, 3, """"stylesBase":3,"styles":[]""", mapOf(0 to row("jkl", 2))))
    streams.assertReused(1, 2)

    // A base that is not the retained table's length is refused on every path.
    assertNull(streams.feed(delta(4, 3, 3, """"stylesBase":9,"styles":[$blue]""", mapOf(2 to row("mno", 1)))))
    streams.assertPublishedFramesUnchanged()
  }

  @Test
  fun styleReplacementRedecodesExactlyTheRowsWhoseStylesChanged() {
    val streams = Differential()
    streams.feed(
      full(1, 3, 4, "[null,$red,$green]", listOf(row("abc", 0), row("def", 1), row("ghi", 2), row("jkl", 1)))
    )

    // The whole table again, equal: no untouched row is decoded again.
    streams.feed(delta(2, 3, 4, """"styles":[null,$red,$green]""", mapOf(0 to row("ABC", 0))))
    streams.assertReused(1, 2, 3)

    // Slot 2 changes: only the row that reads it follows.
    val recolored = streams.feed(delta(3, 3, 4, """"styles":[null,$red,$blue]""", emptyMap()))!!
    streams.assertReused(0, 1, 3)
    assertEquals("#0000FFFF", recolored.cellAt(1, 3)?.style?.foregroundColor?.hex)

    // Slot 1 changes: the two rows that read it follow; slot 2 is past the stable prefix too.
    val swapped = streams.feed(delta(4, 3, 4, """"styles":[null,$green,$blue]""", emptyMap()))!!
    streams.assertReused(0)
    assertEquals("#00FF00FF", swapped.cellAt(1, 2)?.style?.foregroundColor?.hex)

    // The table shrinks below a slot an untouched row still reads: that row loses its style.
    val shrunk = streams.feed(delta(5, 3, 4, """"styles":[null,$green]""", emptyMap()))!!
    streams.assertReused(0, 1, 3)
    assertNull(shrunk.cellAt(1, 3)?.style)

    // It grows back: the row resolves again, with the style now in that slot.
    val regrown = streams.feed(delta(6, 3, 4, """"styles":[null,$green,$red]""", emptyMap()))!!
    streams.assertReused(0, 1, 3)
    assertEquals("#FF0000FF", regrown.cellAt(1, 3)?.style?.foregroundColor?.hex)

    // An append onto a replaced table still splices at the replaced table's length.
    streams.feed(delta(7, 3, 4, """"stylesBase":3,"styles":[$blue]""", mapOf(3 to row("mno", 3))))
    streams.assertReused(0, 1, 2)
    streams.assertPublishedFramesUnchanged()
  }

  // --- Links ---------------------------------------------------------------

  @Test
  fun linkOnlyChangesRedecodeExactlyTheRowsWhoseRunsChanged() {
    val streams = Differential()
    val styles = "[null]"
    fun links(runs: String, targets: String) = ""","links":$runs,"linkTargets":$targets"""
    streams.feed(
      full(
        1, 4, 3, styles, listOf(row("abcd"), row("efgh"), row("ijkl")),
        extra = links("[[0,[[0,2,0]]],[2,[[1,3,1]]]]", """["https://a.example","https://b.example"]""")
      )
    )

    // The same links again: a delta with no rows and no link change decodes nothing.
    val repeated = streams.feed(
      delta(
        2, 4, 3, """"stylesBase":1,"styles":[]""", emptyMap(),
        extra = links("[[0,[[0,2,0]]],[2,[[1,3,1]]]]", """["https://a.example","https://b.example"]""")
      )
    )!!
    streams.assertReused(0, 1, 2)
    assertEquals("https://a.example", repeated.cellAt(1, 1)?.hyperlink)
    assertEquals("https://b.example", repeated.cellAt(4, 3)?.hyperlink)

    // The target table is reordered and the runs follow it: the resolved links are unchanged.
    streams.feed(
      delta(
        3, 4, 3, """"stylesBase":1,"styles":[]""", emptyMap(),
        extra = links("[[0,[[0,2,1]]],[2,[[1,3,0]]]]", """["https://b.example","https://a.example"]""")
      )
    )
    streams.assertReused(0, 1, 2)

    // One row's run moves: a link-only change, no deltaRows.
    val moved = streams.feed(
      delta(
        4, 4, 3, """"stylesBase":1,"styles":[]""", emptyMap(),
        extra = links("[[0,[[1,2,1]]],[2,[[1,3,0]]]]", """["https://b.example","https://a.example"]""")
      )
    )!!
    streams.assertReused(1, 2)
    assertNull(moved.cellAt(1, 1)?.hyperlink)
    assertEquals("https://a.example", moved.cellAt(3, 1)?.hyperlink)

    // One target's text changes under unchanged runs.
    val retargeted = streams.feed(
      delta(
        5, 4, 3, """"stylesBase":1,"styles":[]""", emptyMap(),
        extra = links("[[0,[[1,2,1]]],[2,[[1,3,0]]]]", """["https://c.example","https://a.example"]""")
      )
    )!!
    streams.assertReused(0, 1)
    assertEquals("https://c.example", retargeted.cellAt(2, 3)?.hyperlink)

    // A row gains a link, and a run whose target does not resolve links nothing.
    streams.feed(
      delta(
        6, 4, 3, """"stylesBase":1,"styles":[]""", emptyMap(),
        extra = links(
          "[[0,[[1,2,1]]],[1,[[0,1,9],[2,2,0]]],[2,[[1,3,0]]]]",
          """["https://c.example","https://a.example"]"""
        )
      )
    )
    streams.assertReused(0, 2)

    // The record drops its links: every linked row loses them, the unlinked row stays.
    val unlinked = streams.feed(delta(7, 4, 3, """"stylesBase":1,"styles":[]""", mapOf(1 to row("EFGH"))))!!
    streams.assertReused()
    assertTrue(unlinked.cells.none { it.hyperlink != null })

    // Links with an empty target table link nothing, as on the reference path.
    streams.feed(
      delta(8, 4, 3, """"stylesBase":1,"styles":[]""", emptyMap(), extra = links("[[0,[[0,4,0]]]]", "[]"))
    )
    streams.assertReused(0, 1, 2)
    streams.assertPublishedFramesUnchanged()
  }

  // --- Wide cells ----------------------------------------------------------

  @Test
  fun wideCellsKeepTheirLeadColumnSpanAndLinkAcrossDeltas() {
    val streams = Differential()
    val wide = """[[0,"宽",2,1],[2,"a",1,0],[3,"界",2,1]]"""
    val narrow = """[[0,"x",1,0],[1,"面",2,1],[3,"y",1,0],[4,"z",1,0]]"""
    val first = streams.feed(
      full(
        1, 5, 3, "[null,$red]", listOf(wide, narrow, row("hello")),
        // A run that starts inside a wide cell does not link its lead.
        extra = ""","links":[[0,[[1,3,0]]],[1,[[1,1,0]]]],"linkTargets":["https://a.example"]"""
      )
    )!!
    assertNull(first.cellAt(1, 1)?.hyperlink)
    assertEquals("https://a.example", first.cellAt(4, 1)?.hyperlink)
    assertEquals("面", first.cellAt(3, 2)?.character)
    assertEquals("https://a.example", first.cellAt(3, 2)?.hyperlink)

    // A wide row becomes narrow, so its cell count changes, while its neighbours are reused.
    val second = streams.feed(
      delta(
        2, 5, 3, """"stylesBase":2,"styles":[]""", mapOf(0 to row("plain")),
        extra = ""","links":[[1,[[1,1,0]]]],"linkTargets":["https://a.example"]"""
      )
    )!!
    streams.assertReused(1, 2)
    assertEquals(14, second.cells.size)

    // And back, with a wide cell that ends on the last column.
    streams.feed(
      delta(
        3, 5, 3, """"stylesBase":2,"styles":[]""", mapOf(2 to wide, 0 to """[[3,"字",2,1]]"""),
        extra = ""","links":[[1,[[1,1,0]]]],"linkTargets":["https://a.example"]"""
      )
    )
    streams.assertReused(1)
    streams.assertPublishedFramesUnchanged()
  }

  // --- Size, omitted rows, epoch and reset ---------------------------------

  @Test
  fun sizeChangesReplaceTheBaselineAndRefuseStaleDeltas() {
    val streams = Differential()
    streams.feed(full(1, 3, 2, "[null]", listOf(row("abc"), row("def"))))
    streams.feed(delta(2, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("xyz"))))
    streams.assertReused(1)

    // A delta at another size is refused and leaves the baseline alone.
    assertNull(streams.feed(delta(3, 4, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("wxyz")))))
    assertEquals("keyframe", streams.sessions[0].pendingResyncScope)

    // The keyframe at the new size replaces everything; nothing is carried over.
    val wider = streams.feed(full(4, 4, 3, "[null,$red]", listOf(row("abcd", 1), row("efgh"), row("ijkl"))))!!
    streams.assertReused()
    assertEquals(12, wider.cells.size)
    streams.feed(delta(5, 4, 3, """"stylesBase":2,"styles":[]""", mapOf(2 to row("mnop"))))
    streams.assertReused(0, 1)

    // Shrinking to an empty grid and growing again.
    val empty = streams.feed(full(6, 0, 0, "[null]", emptyList()))!!
    assertTrue(empty.cells.isEmpty())
    assertTrue(empty.cellsByRow.isEmpty())
    streams.feed(full(7, 2, 1, "[null]", listOf(row("ab"))))
    streams.assertPublishedFramesUnchanged()
  }

  @Test
  fun omittedTrailingRowsAreMaterializedAndThenReused() {
    val streams = Differential()
    // Three rows declared, one carried: rows 1 and 2 are omitted as empty.
    streams.feed(full(1, 3, 3, "[null]", listOf(row("abc"))))

    val filled = streams.feed(delta(2, 3, 3, """"stylesBase":1,"styles":[]""", mapOf(2 to row("xyz"))))!!
    streams.assertReused(0)
    assertEquals(setOf(0, 2), filled.cellsByRow.keys)
    assertNull(filled.cellsByRow[1])

    streams.feed(delta(3, 3, 3, """"stylesBase":1,"styles":[]""", mapOf(1 to row("mid"))))
    streams.assertReused(0, 2)

    // A row emptied by a delta leaves the index, and the others stay.
    val emptied = streams.feed(delta(4, 3, 3, """"stylesBase":1,"styles":[]""", mapOf(0 to "[]")))!!
    streams.assertReused(1, 2)
    assertEquals(setOf(1, 2), emptied.cellsByRow.keys)
    streams.assertPublishedFramesUnchanged()
  }

  @Test
  fun epochChangesAndResetDropEveryRetainedRow() {
    val streams = Differential()
    streams.feed(full(1, 3, 2, "[null]", listOf(row("abc"), row("def"))))
    streams.feed(delta(2, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(1 to row("xyz"))))

    // A delta from another epoch is refused; so is one that skips a generation.
    assertNull(streams.feed(delta(3, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("old")), epoch = 6)))
    streams.reset()
    streams.feed(full(1, 3, 2, "[null]", listOf(row("abc"), row("def"))))
    assertNull(
      streams.feed(delta(5, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("gap")), baselineGen = 3))
    )

    // The re-anchoring keyframe starts a new epoch with equal content: nothing is reused.
    streams.feed(full(1, 3, 2, "[null]", listOf(row("abc"), row("def")), epoch = 8))
    streams.assertReused()
    streams.feed(delta(2, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("new")), epoch = 8))
    streams.assertReused(1)

    // After reset a delta has no baseline; the next keyframe starts from nothing.
    streams.reset()
    assertNull(streams.feed(delta(3, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("xyz")), epoch = 8)))
    streams.feed(full(1, 3, 2, "[null]", listOf(row("abc"), row("def")), epoch = 9))
    streams.assertReused()

    // A requested keyframe drops deltas until the keyframe arrives.
    streams.requestKeyframeRecovery()
    assertNull(streams.feed(delta(2, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("xyz")), epoch = 9)))
    streams.feed(full(2, 3, 2, "[null]", listOf(row("abc"), row("def")), epoch = 9))
    streams.feed(delta(3, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(0 to row("xyz")), epoch = 9))
    streams.assertReused(1)
    streams.assertPublishedFramesUnchanged()
  }

  @Test
  fun refusedAndMalformedRecordsLeaveEveryPathInTheSameState() {
    val streams = Differential()
    streams.feed(full(1, 3, 2, "[null,$red]", listOf(row("abc", 1), row("def"))))
    val valid = delta(2, 3, 2, """"stylesBase":2,"styles":[]""", mapOf(0 to row("xyz")))

    // Structural failures throw; budget failures are refused. Neither changes a baseline.
    streams.feed(valid.replace("\"version\":3", "\"version\":4"))
    streams.feed(valid.replace("\"gen\":2,", ""))
    streams.feed(valid.replace("\"width\":3", "\"width\":1.5"))
    streams.feed(valid.replace("[0,\"x\",1,0]", "[2,\"x\",1,0]")) // cells out of column order
    streams.feed(valid.replace("[0,\"x\",1,0]", "[0,\"x\",1,7]")) // a style slot past the table
    streams.feed(valid.replace("[[0,[", "[[5,[")) // a row past the grid
    streams.feed(valid.dropLast(2) + ",}\n") // not JSON
    streams.feed(prefix + "{\"version\":3,\"encoding\":\"other\",\"width\":3,\"height\":2,\"styles\":[]}\n")

    // The session recovers with the next keyframe and deltas reuse rows again.
    streams.feed(full(3, 3, 2, "[null,$red]", listOf(row("abc", 1), row("def"))))
    streams.feed(delta(4, 3, 2, """"stylesBase":2,"styles":[]""", mapOf(0 to row("xyz"))))
    streams.assertReused(1)
    streams.assertPublishedFramesUnchanged()
  }

  // --- Images: forget and recovery, through the poller ---------------------

  private class ScriptedHost(mode: SwiftTUIRowDecoding) {
    private val records = ArrayDeque<ByteArray>()
    val requests = ArrayList<String>()
    val poller = SwiftTUIFramePoller(
      session = SwiftTUIWebSurfaceSession(mode),
      copyLatestFrame = { _, buffer, capacity ->
        val next = records.firstOrNull()
        when {
          next == null -> 0
          buffer == null || capacity < next.size -> next.size
          else -> {
            next.copyInto(buffer)
            records.removeFirst()
            next.size
          }
        }
      },
      requestResync = { _, bytes, count ->
        requests += bytes.decodeToString(0, count)
        1
      }
    )

    fun present(record: String?): SwiftTUIFramePollResult {
      if (record != null) records.addLast(record.encodeToByteArray())
      return poller.poll(1L)
    }
  }

  @Test
  fun imageForgetAndRecoveryPublishEqualFramesAndRequests() {
    val hosts = SwiftTUIRowDecoding.entries.map { ScriptedHost(it) }
    fun image(payload: Boolean) =
      """[{"id":"png:forgotten:86","format":"png","bounds":[0,0,1,1],"visibleBounds":[0,0,1,1],""" +
        """"scalingMode":"stretch","opacity":1.0,"pixelSize":[2,2]""" +
        (if (payload) ""","dataBase64":"aGk="""" else "") + "}]"
    fun keyframe(gen: Int) =
      full(gen, 3, 2, "[null]", listOf(row("abc"), row("def"))).replace("\"images\":[]", "\"images\":${image(true)}")
    fun change(gen: Int, payload: Boolean) =
      delta(gen, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(1 to row("xyz")))
        .replace("\"deltaRows\"", "\"images\":${image(payload)},\"deltaRows\"")

    fun step(record: String?, missing: List<String> = emptyList()): SwiftTUIFramePollResult {
      val results = hosts.map { host ->
        host.poller.reportMissingImagePayloads(missing)
        host.present(record)
      }
      results.forEachIndexed { index, result ->
        assertEquals(results[0], result)
        assertEquals(hosts[0].requests, hosts[index].requests)
        assertEquals(hosts[0].poller.epoch, hosts[index].poller.epoch)
      }
      return results[0]
    }

    // Transmit-once: the keyframe carries the payload, the delta does not.
    assertTrue(step(keyframe(1)) is SwiftTUIFramePollResult.Frame)
    val hit = step(change(2, payload = false)) as SwiftTUIFramePollResult.Frame
    assertNull(hit.value.imageAttachments.single().payloadBase64)
    assertFalse(hit.isImagePayloadRepair)

    // The painter forgot the bitmap: the poller requests it, on every path, once.
    assertEquals(SwiftTUIFramePollResult.None, step(null, missing = listOf("png:forgotten:86")))
    assertEquals(listOf("""{"scope":"images","ids":["png:forgotten:86"]}"""), hosts[0].requests)

    // The repair re-encodes the same application frame with the payload.
    val repair = step(change(3, payload = true)) as SwiftTUIFramePollResult.Frame
    assertTrue(repair.isImagePayloadRepair)
    assertEquals("aGk=", repair.value.imageAttachments.single().payloadBase64)

    // A lost baseline while an image is on screen recovers through a keyframe.
    assertEquals(SwiftTUIFramePollResult.None, step(change(9, payload = false)))
    assertEquals("""{"scope":"keyframe"}""", hosts[0].requests.last())
    assertTrue(step(keyframe(4)) is SwiftTUIFramePollResult.Frame)
    assertTrue(step(change(5, payload = false)) is SwiftTUIFramePollResult.Frame)

    // A session replacement (new epoch) forgets outstanding image requests alike.
    val replaced = step(keyframe(1).replace("\"epoch\":7", "\"epoch\":8")) as SwiftTUIFramePollResult.Frame
    assertTrue(replaced.isSessionReplacement)
  }

  // --- Accessibility -------------------------------------------------------

  @Test
  fun anEditorsTextSelectionIsPublishedEquallyWhileItsRowsAreReused() {
    // The input-method mirror reads the focused editor's text and selection
    // from the frame's accessibility node, so the paths must publish the same
    // node, selection included, whether or not a record replaces a row.
    fun editor(role: String, selection: String) =
      ""","accessibilityTree":[{"id":"f","rect":[0,0,3,1],"role":"$role","isFocused":true,""" +
        """"actionTarget":"f#1","actions":["focus","setValue","editText","selectText"],""" +
        """"value":{"type":"text","value":"a\uD83D\uDE00b"},"textSelection":$selection}]"""
    val noNewStyles = """"stylesBase":1,"styles":[]"""
    val streams = Differential()
    val first = streams.feed(
      full(1, 3, 2, "[null]", listOf(row("abc"), row("def")), extra = editor("textField", "[3,1]"))
    )!!
    assertEquals(SwiftTUITextSelection(anchor = 3, head = 1), first.accessibilityNodes.single().textSelection)

    // The selection moves and one row is replaced: the other row is reused.
    val moved = streams.feed(
      delta(2, 3, 2, noNewStyles, mapOf(0 to row("xyz")), extra = editor("textField", "[0,4]"))
    )!!
    streams.assertReused(1)
    assertEquals(SwiftTUITextSelection(anchor = 0, head = 4), moved.accessibilityNodes.single().textSelection)

    // A selection that is not a pair, and a secure field's, are both absent.
    val malformed = streams.feed(
      delta(3, 3, 2, noNewStyles, mapOf(1 to row("uvw")), extra = editor("textEditor", "[2]"))
    )!!
    streams.assertReused(0)
    assertNull(malformed.accessibilityNodes.single().textSelection)
    val secure = streams.feed(
      delta(4, 3, 2, noNewStyles, mapOf(0 to row("abc")), extra = editor("secureField", "[1,1]"))
    )!!
    streams.assertReused(1)
    assertNull(secure.accessibilityNodes.single().textSelection)
    streams.assertPublishedFramesUnchanged()
  }

  // --- Corpora -------------------------------------------------------------

  @Test
  fun conformanceCorpusPublishesEqualFramesOnEveryPath() {
    val corpus = WebSurfaceConformanceLoader.load()
    var emitted = 0
    for (fixture in corpus.fixtures.values) {
      if (fixture.entry.kind != "record" && fixture.entry.kind != "web-painter") continue
      // Once as delivered (drops applied) and once with every record delivered.
      for (applyDrops in listOf(true, false)) {
        val streams = Differential()
        fixture.steps.forEachIndexed { index, step ->
          when {
            step.has("emit") && !(applyDrops && index in fixture.droppedEmitIndices) -> {
              streams.feed(step.getString("emit"))
              emitted += 1
            }
            step.has("reconnect") -> streams.reset()
          }
        }
        streams.assertPublishedFramesUnchanged()
      }
    }
    assertTrue("the corpus must deliver records", emitted > 40)
  }

  @Test
  fun measurementWorkloadsPublishEqualFramesOnEveryPath() {
    for ((width, height) in DecodedRowWorkloads.grids) {
      for (name in DecodedRowWorkloads.names) {
        val workload = DecodedRowWorkloads.build(name, width, height)
        val stream = DecodedRowStream(workload)
        val streams = Differential()
        assertNotNull(streams.feed(stream.keyframe().decodeToString()))
        // The small grid runs each cycle whole and wraps; the large grid runs its first steps.
        val operations = if (width > 100) 8 else workload.steps.size + 3
        repeat(operations) { assertNotNull(workload.id, streams.feed(stream.next().decodeToString())) }
        val reused = streams.reusedRows(SwiftTUIRowDecoding.RetainedRows).size
        when (name) {
          "one-cell", "one-row", "image-one-cell", "linked-one-cell" -> assertEquals(height - 1, reused)
          "scroll" -> assertEquals(2, reused)
          else -> assertEquals(0, reused)
        }
        streams.assertPublishedFramesUnchanged()
      }
    }
  }

  // --- Seeded random streams -----------------------------------------------

  /**
   * Proposes records around a model of the stream. The model advances only
   * when the reference path accepted the record, so it never has to predict a
   * refusal.
   */
  private class RecordFuzzer(seed: Long, private val prefix: String) {
    private val random = Random(seed)
    private val stylePool = listOf(
      "null", """{"fg":"#FF0000FF"}""", """{"fg":"#00FF00FF","em":1}""", """{"bg":"#0000FFFF"}""",
      """{"fg":"#FFFFFFFF","opacity":0.5}""", """{"em":18}""",
      """{"underline":{"pattern":"double","color":"#112233FF"}}""",
      """{"fg":"#445566FF","strikethrough":{"pattern":"solid"}}"""
    )
    private val texts = listOf("a", "b", "c", " ", "", "é", "😀", "#")
    private val wideTexts = listOf("宽", "界", "面")
    private val targetPool = listOf(
      "https://a.example", "https://b.example", "https://c.example/path", "mailto:d@example.com"
    )

    class Proposal(
      val record: String,
      /** Rows a delta replaces; `null` for a full frame. */
      val replacedRows: Set<Int>?,
      val commit: () -> Unit
    )

    private var epoch = 1
    private var generation = 0
    private var width = 0
    private var height = 0
    private var styles: List<String> = listOf("null")
    private var linkRows: Map<Int, List<IntArray>> = emptyMap()
    private var targets: List<String> = emptyList()
    private var images = "[]"
    private var hasBaseline = false
    var needsKeyframe = true

    fun forgetBaseline() {
      hasBaseline = false
      needsKeyframe = true
    }

    fun propose(): Proposal {
      if (!hasBaseline || (needsKeyframe && random.nextInt(10) < 8)) return full()
      val roll = random.nextInt(100)
      return when {
        roll < 8 -> full()
        roll < 20 -> invalid()
        else -> delta()
      }
    }

    private fun randomStyles(count: Int): List<String> = List(count) { stylePool[random.nextInt(stylePool.size)] }

    private fun randomRow(rowWidth: Int, styleCount: Int): String {
      if (random.nextInt(14) == 0) return "[]"
      val cells = ArrayList<String>()
      var column = 0
      while (column < rowWidth) {
        if (random.nextInt(7) == 0) {
          column += 1
          continue
        }
        if (random.nextInt(12) == 0) break
        val wide = random.nextInt(5) == 0 && column + 2 <= rowWidth
        val text = if (wide) wideTexts[random.nextInt(wideTexts.size)] else texts[random.nextInt(texts.size)]
        val span = if (wide) 2 else 1
        cells.add("""[$column,"$text",$span,${random.nextInt(styleCount)}]""")
        column += span
      }
      return cells.joinToString(",", "[", "]")
    }

    private fun randomLinkRows(gridWidth: Int, gridHeight: Int, targetCount: Int): Map<Int, List<IntArray>> {
      if (gridWidth == 0 || gridHeight == 0) return emptyMap()
      val rows = sortedMapOf<Int, List<IntArray>>()
      for (y in 0 until gridHeight) {
        if (random.nextInt(3) != 0) continue
        val runs = ArrayList<IntArray>()
        var column = random.nextInt(gridWidth)
        while (column < gridWidth && runs.size < 3) {
          val span = random.nextInt(minOf(4, gridWidth - column) + 1) // may be empty
          // Now and then a target slot the table does not have.
          val target = if (random.nextInt(9) == 0) targetCount + 1 else random.nextInt(maxOf(1, targetCount))
          runs.add(intArrayOf(column, span, target))
          column += span + random.nextInt(3)
        }
        if (runs.isNotEmpty()) rows[y] = runs
      }
      return rows
    }

    private fun linksText(rows: Map<Int, List<IntArray>>, table: List<String>): String {
      if (rows.isEmpty()) return ""
      val runs = rows.entries.joinToString(",", "[", "]") { (y, rowRuns) ->
        "[$y,${rowRuns.joinToString(",", "[", "]") { "[${it[0]},${it[1]},${it[2]}]" }}]"
      }
      return ""","links":$runs,"linkTargets":${table.joinToString(",", "[", "]") { "\"$it\"" }}"""
    }

    private fun randomImages(): String = when (random.nextInt(6)) {
      0 -> """[{"id":"png:one:1","format":"png","bounds":[0,0,1,1],"visibleBounds":[0,0,1,1],""" +
        """"scalingMode":"fit","pixelSize":[1,1],"dataBase64":"aGk="}]"""
      1 -> """[{"id":"png:one:1","format":"png","bounds":[0,0,1,1],"visibleBounds":[0,0,1,1],""" +
        """"scalingMode":"fit","opacity":0.5,"pixelSize":[1,1]}]"""
      else -> "[]"
    }

    private fun stamp(delta: Boolean, stamped: Boolean, nextGeneration: Int, baseline: Int): String =
      if (!stamped) "" else {
        """"epoch":$epoch,"gen":$nextGeneration,""" + if (delta) """"baselineGen":$baseline,""" else ""
      }

    private fun full(): Proposal {
      val nextEpoch = if (random.nextInt(5) == 0) epoch + 1 else epoch
      val nextWidth = listOf(0, 1, 2, 5, 8, 12)[random.nextInt(6)]
      val nextHeight = if (nextWidth == 0) random.nextInt(2) else listOf(1, 2, 3, 6)[random.nextInt(4)]
      val nextStyles = listOf("null") + randomStyles(random.nextInt(5))
      // Sometimes the encoder omits trailing rows.
      val carried = if (random.nextInt(5) == 0) random.nextInt(nextHeight + 1) else nextHeight
      val rows = List(carried) { randomRow(nextWidth, nextStyles.size) }
      val nextTargets = targetPool.shuffled(random).take(random.nextInt(targetPool.size + 1))
      val nextLinks = randomLinkRows(nextWidth, carried, nextTargets.size)
      val nextImages = randomImages()
      val stamped = random.nextInt(20) != 0
      val nextGeneration = generation + 1 + random.nextInt(2)
      val previousEpoch = epoch
      epoch = nextEpoch
      val text = prefix + """{"version":${1 + random.nextInt(2)},${stamp(false, stamped, nextGeneration, 0)}""" +
        """"sequence":$nextGeneration,"width":$nextWidth,"height":$nextHeight,""" +
        """"styles":${nextStyles.joinToString(",", "[", "]")},"rows":${rows.joinToString(",", "[", "]")},""" +
        """"images":$nextImages${linksText(nextLinks, nextTargets)}}""" + "\n"
      epoch = previousEpoch
      return Proposal(text, replacedRows = null) {
        epoch = nextEpoch
        generation = nextGeneration
        width = nextWidth
        height = nextHeight
        styles = nextStyles
        linkRows = nextLinks
        targets = nextTargets
        images = nextImages
        hasBaseline = true
        needsKeyframe = false
      }
    }

    private fun delta(
      baseline: Int = generation,
      deltaEpoch: Int = epoch,
      deltaWidth: Int = width,
      styleBaseOffset: Int = 0,
      rowOffset: Int = 0
    ): Proposal {
      // Styles: the negotiated append shape, or the whole table again.
      val append = random.nextInt(10) < 6
      val nextStyles: List<String> = if (append) {
        styles + randomStyles(random.nextInt(3))
      } else when (random.nextInt(5)) {
        0 -> styles.toList()
        1 -> styles.toMutableList().also { if (it.size > 1) it[1 + random.nextInt(it.size - 1)] = stylePool[random.nextInt(stylePool.size)] }
        2 -> styles.take(1 + random.nextInt(styles.size))
        3 -> styles + randomStyles(1 + random.nextInt(2))
        else -> listOf("null") + randomStyles(random.nextInt(4))
      }
      val stylesText = if (append) {
        """"stylesBase":${styles.size + styleBaseOffset},"styles":${nextStyles.drop(styles.size).joinToString(",", "[", "]")}"""
      } else {
        """"styles":${nextStyles.joinToString(",", "[", "]")}"""
      }
      val replaced = (0 until height).filter { random.nextInt(4) == 0 }.toSortedSet()
      val rows = replaced.joinToString(",", "[", "]") { "[${it + rowOffset},${randomRow(width, nextStyles.size)}]" }
      // Links: mostly unchanged, sometimes one of the link-only changes.
      var nextLinks = linkRows
      var nextTargets = targets
      when (random.nextInt(20)) {
        0, 1, 2 -> nextLinks = randomLinkRows(width, height, targets.size)
        3 -> nextLinks = emptyMap()
        4, 5 -> { // the same resolved targets through a reordered table
          val order = targets.indices.shuffled(random)
          nextTargets = order.map { targets[it] }
          nextLinks = linkRows.mapValues { (_, runs) ->
            runs.map { run -> intArrayOf(run[0], run[1], order.indexOf(run[2]).let { if (it < 0) run[2] else it }) }
          }
        }
        6 -> if (targets.isNotEmpty()) { // one target's text changes under unchanged runs
          nextTargets = targets.toMutableList().also { it[random.nextInt(it.size)] = "https://changed.example/${random.nextInt(3)}" }
        }
        7 -> nextTargets = targetPool.shuffled(random).take(random.nextInt(targetPool.size + 1))
      }
      val nextImages = if (random.nextInt(4) == 0) randomImages() else images
      val stamped = random.nextInt(25) != 0
      val nextGeneration = generation + 1
      val damage = if (random.nextBoolean()) "" else
        ""","damage":{"textRows":${replaced.joinToString(",", "[", "]") { "[$it,[[0,$width]]]" }},""" +
          """"requiresFullTextRepaint":${random.nextInt(8) == 0},"requiresFullGraphicsReplay":false}"""
      val text = prefix + """{"version":3,"encoding":"delta",""" +
        (if (stamped) """"epoch":$deltaEpoch,"gen":$nextGeneration,"baselineGen":$baseline,""" else "") +
        """"sequence":$nextGeneration,"width":$deltaWidth,"height":$height,$stylesText,"deltaRows":$rows,""" +
        """"images":$nextImages$damage${linksText(nextLinks, nextTargets)}}""" + "\n"
      return Proposal(text, replacedRows = replaced) {
        generation = nextGeneration
        styles = nextStyles
        linkRows = nextLinks
        targets = nextTargets
        images = nextImages
      }
    }

    private fun invalid(): Proposal = when (random.nextInt(7)) {
      0 -> delta(baseline = generation + 3)
      1 -> delta(deltaEpoch = epoch + 9)
      2 -> delta(deltaWidth = width + 1)
      3 -> delta(styleBaseOffset = 1)
      4 -> delta(rowOffset = height)
      5 -> Proposal(delta().record.replace("\"version\":3", "\"version\":9"), null) {}
      else -> Proposal(delta().record.replace("\"encoding\":\"delta\",", "\"encoding\":\"delta\",\"epoch\":1,"), null) {}
    }
  }

  @Test
  fun seededRandomStreamsPublishEqualFramesOnEveryPath() {
    var frames = 0
    var deltaFrames = 0
    var rowsReused = 0
    var untouchedRowsRedecoded = 0
    var refused = 0
    for (seed in 1L..160L) {
      val streams = Differential()
      val fuzzer = RecordFuzzer(seed * 300, prefix)
      repeat(140) { step ->
        if (step % 47 == 46) {
          streams.reset()
          fuzzer.forgetBaseline()
        }
        val proposal = fuzzer.propose()
        val frame = streams.feed(proposal.record)
        if (frame == null) {
          refused += 1
          if (streams.sessions[0].pendingResyncScope != null) fuzzer.needsKeyframe = true
        } else {
          proposal.commit()
          frames += 1
          val replaced = proposal.replacedRows
          if (replaced != null) {
            deltaFrames += 1
            val reused = streams.reusedRows(SwiftTUIRowDecoding.RetainedRows)
            // A replaced row is never served from the retained cells.
            assertTrue("seed $seed step $step", reused.none { it in replaced })
            rowsReused += reused.size
            untouchedRowsRedecoded += streams.rowsWithCells().count { it !in replaced && it !in reused }
          }
        }
      }
      streams.assertPublishedFramesUnchanged()
    }
    // The streams must exercise what they claim to: accepted deltas, reuse,
    // style- and link-driven decoding of untouched rows, and refusals.
    assertTrue("frames $frames", frames > 12_000)
    assertTrue("delta frames $deltaFrames", deltaFrames > 8_000)
    assertTrue("rows reused $rowsReused", rowsReused > 8_000)
    assertTrue("untouched rows decoded again $untouchedRowsRedecoded", untouchedRowsRedecoded > 1_500)
    assertTrue("refused $refused", refused > 1_000)
    println(
      "random streams: frames=$frames deltaFrames=$deltaFrames rowsReused=$rowsReused " +
        "untouchedRowsRedecoded=$untouchedRowsRedecoded refused=$refused"
    )
  }

  // --- Ownership -----------------------------------------------------------

  /** Decodes a keyframe and a delta that replaces row 1; returns weak handles on both rows' first cells. */
  private fun replaceRowOne(session: SwiftTUIWebSurfaceSession): Pair<WeakReference<SwiftTUICell>, WeakReference<SwiftTUICell>> {
    val first = session.decode(full(1, 3, 2, "[null]", listOf(row("abc"), row("def"))))!!
    val kept = WeakReference(first.cells.first { it.y == 0 })
    val replaced = WeakReference(first.cells.first { it.y == 1 })
    session.decode(delta(2, 3, 2, """"stylesBase":1,"styles":[]""", mapOf(1 to row("xyz"))))!!
    return kept to replaced
  }

  private fun collected(reference: WeakReference<*>): Boolean {
    repeat(50) {
      if (reference.get() == null) return true
      System.gc()
      Thread.sleep(10)
    }
    return reference.get() == null
  }

  @Test
  fun replacedRowsAreReleasedWithTheFramesThatPublishedThem() {
    val session = SwiftTUIWebSurfaceSession(SwiftTUIRowDecoding.RetainedRows)
    // Both published frames are dropped here; only the session can still hold cells.
    val (kept, replaced) = replaceRowOne(session)
    assertTrue("a replaced row must not outlive its frames", collected(replaced))
    assertNotNull("the active baseline's row is retained", kept.get())
    assertEquals(2, session.retainedRowCells()!!.size)

    session.reset()
    assertNull(session.retainedRowCells())
    assertTrue("reset releases the baseline's rows", collected(kept))
  }

  @Test
  fun aRowIndexBuiltFromChunksMatchesGroupingAnyOtherCellList() {
    val session = SwiftTUIWebSurfaceSession(SwiftTUIRowDecoding.RetainedRows)
    val frame = session.decode(full(1, 3, 3, "[null]", listOf(row("abc"), "[]", row("de"))))!!
    assertTrue(frame.cells is SwiftTUIRowCells)
    // A frame built from any other list still indexes by grouping.
    val regrouped = frame.copy(cells = frame.cells.toList())
    assertFalse(regrouped.cells is SwiftTUIRowCells)
    assertEquals(regrouped.cellsByRow, frame.cellsByRow)
    assertEquals(listOf(0, 2), frame.cellsByRow.keys.toList())
    assertEquals(5, frame.cells.size)
    assertEquals("d", frame.cells[3].character)
    assertSame(frame.cellsByRow, frame.cellsByRow)
  }
}
