package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wave-2 regression hunt, part CB (STUI-1165): neighbours of the STUI-1144
 * omitted-trailing-row repair. The coordination root's wave-2 hunt evidence
 * owns the case descriptions (w2cb013 to w2cb016).
 */
class Wave2HuntCbSurfaceTest {
  private fun record(json: String) = SwiftTUIWebSurfaceSession.RECORD_PREFIX + json + "\n"

  private fun full(
    width: Int,
    height: Int,
    rows: String,
    stamp: String = "",
    extra: String = ""
  ) = record(
    """{"version":2,$stamp"width":$width,"height":$height,"styles":[null],""" +
      """"rows":$rows$extra}"""
  )

  private fun delta(
    width: Int,
    height: Int,
    deltaRows: String,
    stamp: String = "",
    styles: String = "[null]",
    extra: String = ""
  ) = record(
    """{"version":3,"encoding":"delta",$stamp"width":$width,"height":$height,""" +
      """"styles":$styles,"deltaRows":$deltaRows$extra}"""
  )

  private fun stamp(epoch: Int, gen: Int, baseline: Int? = null) =
    """"epoch":$epoch,"gen":$gen,""" + (baseline?.let { """"baselineGen":$it,""" } ?: "")

  private fun SwiftTUIFrame.glyphs() = cells.map { Triple(it.x, it.y, it.character) }

  @Test
  fun w2cb013LastRowOfTheMaximalGridIsAddressableOverAnEmptyBaseline() {
    val session = SwiftTUIWebSurfaceSession()
    assertNotNull(session.decode(full(64, 1024, "[]", stamp(9, 1))))
    val populated = session.decode(
      delta(64, 1024, """[[1023,[[0,"Z",1,0]]]]""", stamp(9, 2, 1))
    )
    val moved = session.decode(
      delta(64, 1024, """[[0,[[0,"A",1,0]]],[1023,[]]]""", stamp(9, 3, 2))
    )
    assertEquals(
      listOf(listOf(Triple(0, 1023, "Z")), listOf(Triple(0, 0, "A"))),
      listOf(populated?.glyphs(), moved?.glyphs())
    )
    assertNull(session.pendingResyncScope)
  }

  @Test
  fun w2cb014MaterializedBaselineDecodesLikeExplicitEmptyRows() {
    val style = """{"fg":"#FF0000FF"}"""
    val link = """"linkTargets":["https://example.com/z"],"links":[[3,[[0,1,0]]]]"""
    val viaDelta = SwiftTUIWebSurfaceSession()
    assertNotNull(viaDelta.decode(full(3, 4, """[[[0,"A",1,0]]]""", stamp(1, 1))))
    val materialized = viaDelta.decode(
      delta(
        3,
        4,
        """[[3,[[0,"Z",1,1]]]]""",
        stamp(1, 2, 1) + """"stylesBase":1,""",
        styles = "[$style]",
        extra = ",$link"
      )
    )
    val explicit = SwiftTUIWebSurfaceSession().decode(
      record(
        """{"version":2,"epoch":1,"gen":1,"width":3,"height":4,""" +
          """"styles":[null,$style],"rows":[[[0,"A",1,0]],[],[],[[0,"Z",1,1]]],$link}"""
      )
    )
    assertNotNull(materialized)
    assertEquals(explicit?.cells, materialized?.cells)
    assertEquals("https://example.com/z", materialized?.cells?.last()?.hyperlink)
  }

  @Test
  fun w2cb015PopulatedOmittedRowIsPatchedAloneByTheDamagePlanner() {
    val session = SwiftTUIWebSurfaceSession()
    val baseline = session.decode(full(4, 3, """[[[0,"A",1,0]]]""", stamp(4, 1)))
    val frame = session.decode(
      delta(
        4,
        3,
        """[[2,[[1,"Q",1,0]]]]""",
        stamp(4, 2, 1),
        extra = ""","damage":{"textRows":[[2,[[1,2]]]],""" +
          """"requiresFullTextRepaint":false,"requiresFullGraphicsReplay":false}"""
      )
    )
    assertNotNull(baseline)
    assertNotNull(frame)
    val plan = SwiftTUIDamagePlan.plan(
      frame!!,
      previousSequence = baseline!!.consumedGeneration,
      sizeChanged = false
    )
    val visited = mutableListOf<String>()
    val examined = SwiftTUIDamagePlan.forEachDamagedCell(frame, plan) { visited += it.character }
    assertFalse(plan.fullRepaint)
    assertEquals(listOf(SwiftTUIDamagePlan.RowDamage(2, listOf(1..1))), plan.rows)
    assertEquals(listOf("Q"), visited)
    assertEquals(1, examined)
  }

  @Test
  fun w2cb016OmittedRowDeltasKeepTheStylesBaseSizeAndResizeGuards() {
    val stylesBase = SwiftTUIWebSurfaceSession()
    stylesBase.decode(full(2, 3, """[[[0,"A",1,0]]]""", stamp(1, 1)))
    val wrongBase = stylesBase.outcome(
      delta(2, 3, """[[2,[[0,"B",1,0]]]]""", stamp(1, 2, 1) + """"stylesBase":5,""", styles = "[]")
    )

    val size = SwiftTUIWebSurfaceSession()
    size.decode(full(2, 3, """[[[0,"A",1,0]]]"""))
    val wrongSize = size.outcome(delta(3, 3, """[[2,[[0,"B",1,0]]]]"""))

    val resized = SwiftTUIWebSurfaceSession()
    resized.decode(full(2, 3, """[[[0,"A",1,0]]]""", stamp(1, 1)))
    resized.decode(full(2, 2, "[]", stamp(2, 1)))
    val inside = resized.outcome(delta(2, 2, """[[1,[[0,"C",1,0]]]]""", stamp(2, 2, 1)))
    val beyond = resized.outcome(delta(2, 2, """[[2,[[0,"D",1,0]]]]""", stamp(2, 3, 2)))

    assertEquals(
      listOf(
        "stylesBase" to "refused keyframe",
        "size" to "refused none",
        "resized inside" to "[(0, 1, C)] none",
        "resized beyond" to "refused keyframe"
      ),
      listOf(
        "stylesBase" to wrongBase,
        "size" to wrongSize,
        "resized inside" to inside,
        "resized beyond" to beyond
      )
    )
  }

  /** Decodes one record and reports the cells (or refusal) with the pending resync scope. */
  private fun SwiftTUIWebSurfaceSession.outcome(payload: String): String {
    val decoded = decode(payload)?.glyphs()?.toString() ?: "refused"
    return "$decoded ${pendingResyncScope ?: "none"}"
  }
}
