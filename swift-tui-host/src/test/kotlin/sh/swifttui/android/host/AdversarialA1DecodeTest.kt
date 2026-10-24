package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import org.json.JSONArray
import org.json.JSONObject

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1DecodeTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (76..95).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    val session = SwiftTUIWebSurfaceSession()
    fun decode(record: JSONObject) = session.decode(a1Record(record))
    fun full(record: JSONObject = a1Full()) = requireNotNull(decode(record))
    when (scenario) {
      76 -> {
        val f = full(a1Full().put("version", 1))
        assertEquals("A", f.cellAt(1, 1)?.character)
        assertEquals(SwiftTUIFocusPresentation.None, f.focusPresentation)
        assertEquals(SwiftTUITerminalStyle.Default, f.terminalStyle)
      }
      77 -> assertEquals(9L, full(a1Full(9)).sequence)
      78 -> {
        val f = full(a1Full().put("rows", JSONArray("[[[0,\"界\",2,0]]]")))
        assertEquals("界", f.cellAt(1, 1)?.character)
        assertSame(f.cellAt(1, 1), f.cellAt(2, 1))
      }
      79 -> {
        val f = full(a1Full().put("height", 2))
        assertEquals(2, f.gridHeight)
        assertNull(f.cellAt(1, 2))
      }
      80, 81 -> {
        full()
        val delta = a1Delta()
        if (scenario == 81) delta.put("deltaRows", JSONArray("[[0,[]]]"))
        val f = requireNotNull(decode(delta))
        assertEquals(if (scenario == 80) listOf("Z") else emptyList<String>(), f.cells.map { it.character })
      }
      82 -> {
        assertEquals(1L, full().consumedGeneration)
        assertEquals(2L, full(a1Full(99)).consumedGeneration)
      }
      83 -> { full(); session.reset(); assertNull(decode(a1Delta())) }
      84 -> { full(); assertNull(decode(a1Delta().put("width", 3))) }
      85 -> {
        full()
        val delta = a1Delta().put("stylesBase", 1)
          .put("styles", JSONArray("[{\"fg\":\"#FF0000FF\"}]"))
          .put("deltaRows", JSONArray("[[0,[[0,\"Z\",1,1],[1,\"B\",1,0]]]]"))
        val f = requireNotNull(decode(delta))
        assertEquals("#FF0000FF", f.cellAt(1, 1)?.style?.foregroundColor?.hex)
        assertNull(f.cellAt(2, 1)?.style)
      }
      86 -> {
        full()
        assertNull(decode(a1Delta().put("stylesBase", 2)))
        assertEquals("keyframe", session.pendingResyncScope)
      }
      87, 88, 89 -> {
        full(a1Stamp(a1Full(), 7, 1))
        val delta = a1Stamp(a1Delta(), if (scenario == 89) 8 else 7,
          if (scenario == 88) 3 else 2, if (scenario == 88) 2 else 1)
        if (scenario == 87) {
          assertEquals("Z", requireNotNull(decode(delta)).cells.single().character)
          assertNull(session.pendingResyncScope)
        } else {
          assertNull(decode(delta))
          assertEquals("keyframe", session.pendingResyncScope)
        }
      }
      90 -> {
        full(a1Stamp(a1Full(), 7, 1))
        assertNull(decode(a1Stamp(a1Delta(), 7, 3, 2)))
        assertEquals("keyframe", session.pendingResyncScope)
        full(a1Stamp(a1Full(9), 8, 1))
        assertNull(session.pendingResyncScope)
      }
      91 -> {
        val f = full(a1Full().put("futureField", "yes")
          .put("focusPresentation", JSONObject("{\"semantics\":\"future-focus\"}")))
        assertEquals(listOf("A", "B"), f.cells.map { it.character })
        assertEquals("future-focus", f.focusPresentation.semantics)
      }
      92 -> {
        val f = full(a1Full().put("linkTargets", JSONArray("[\"https://example.test/a\"]"))
          .put("links", JSONArray("[[0,[[0,1,0]]]]")))
        assertEquals("https://example.test/a", f.cellAt(1, 1)?.hyperlink)
        assertNull(f.cellAt(2, 1)?.hyperlink)
      }
      93 -> {
        val record = a1Full().put("width", 3).put("damage", JSONObject(
          "{\"textRows\":[[0,[[1,3],[0,2]]]],\"requiresFullTextRepaint\":false,\"requiresFullGraphicsReplay\":false}"))
        val f = full(record)
        val p = SwiftTUIDamagePlan.plan(f, 0, false)
        assertFalse(p.fullRepaint)
        assertEquals(listOf(0..2), p.rows.single().columnRanges)
      }
      94 -> {
        val invalid = a1Full().put("rows", JSONArray("[[[0,\"界\",2,0],[1,\"B\",1,0]]]"))
        assertNull(decode(invalid))
        assertEquals("keyframe", session.pendingResyncScope)
      }
      95 -> {
        assertNull(decode(a1Full().put("rows", JSONArray("[[[2,\"X\",1,0]]]"))))
        val f = full()
        assertEquals(1L, f.consumedGeneration)
        assertNull(session.pendingResyncScope)
      }
      else -> error(id)
    }
  }
}
