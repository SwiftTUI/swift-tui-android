package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SwiftTUIDamageTraversalTest {
  private val prefix = SwiftTUIWebSurfaceSession.RECORD_PREFIX
  private fun row(width: Int): String = (0 until width).joinToString(",", "[", "]") { "[$it,\"a\",1,0]" }
  private fun full(width: Int, height: Int): String = prefix +
    """{"version":2,"epoch":7,"gen":1,"width":$width,"height":$height,"styles":[null],"rows":${(0 until height).joinToString(",", "[", "]") { row(width) }}}"""

  @Test
  fun decodedDeltaPreservesOneCellDamageAndVisitsOnlyItsRow() {
    val session = SwiftTUIWebSurfaceSession()
    val first = session.decode(full(80, 24))!!
    val next = session.decode(prefix +
      """{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":80,"height":24,"styles":[null],"deltaRows":[[3,${row(80)}]],"damage":{"textRows":[[3,[[10,11]]]],"requiresFullTextRepaint":false,"requiresFullGraphicsReplay":false}}"""
    )!!
    assertEquals(listOf(3), next.dirtyRows)
    val plan = SwiftTUIDamagePlan.plan(next, first.consumedGeneration, false)
    assertFalse(plan.fullRepaint)
    assertEquals(listOf(10..10), plan.rows.single().columnRanges)
    val selected = mutableListOf<SwiftTUICell>()
    val visits = SwiftTUIDamagePlan.forEachDamagedCell(next, plan, selected::add)
    assertEquals(listOf(10 to 3), selected.map { it.x to it.y })
    assertEquals(80, visits)
    assertSame(next.cellsByRow, next.cellsByRow)
  }

  @Test
  fun detailedDamageClampsMergesAndOverridesSummaryButEmptyDetailsMeanWholeRow() {
    val base = SwiftTUIWebSurfaceSession().decode(full(8, 3))!!
    val next = base.copy(consumedGeneration = 2, dirtyRows = listOf(0, 1, 2, 9),
      textDamageRows = listOf(
        SwiftTUITextDamageRow(0, listOf(SwiftTUIRange(-2, 2), SwiftTUIRange(1, 4), SwiftTUIRange(6, 99))),
        SwiftTUITextDamageRow(1, emptyList()),
        SwiftTUITextDamageRow(-1, emptyList())
      ), requiresFullTextRepaint = false, requiresFullGraphicsReplay = false)
    val plan = SwiftTUIDamagePlan.plan(next, 1, false)
    assertEquals(listOf(0, 1, 2), plan.rows.map { it.row })
    assertEquals(listOf(0..3, 6..7), plan.rows[0].columnRanges)
    assertEquals(listOf(0..7), plan.rows[1].columnRanges)
    assertEquals(listOf(0..7), plan.rows[2].columnRanges)
  }

  @Test
  fun wideLeadCellOverlappingDamageIsSelectedOnce() {
    val session = SwiftTUIWebSurfaceSession()
    val first = session.decode(full(4, 1))!!
    val next = session.decode(prefix +
      """{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":4,"height":1,"styles":[null],"deltaRows":[[0,[[0,"a",1,0],[1,"界",2,0],[3,"z",1,0]]]],"damage":{"textRows":[[0,[[2,3],[1,2]]]],"requiresFullTextRepaint":false,"requiresFullGraphicsReplay":false}}"""
    )!!
    val selected = mutableListOf<SwiftTUICell>()
    val plan = SwiftTUIDamagePlan.plan(next, first.consumedGeneration, false)
    assertEquals(3, SwiftTUIDamagePlan.forEachDamagedCell(next, plan, selected::add))
    assertEquals(listOf("界"), selected.map { it.character })
  }

  @Test
  fun allDamagedRowsVisitTheGridOnceAfterIndexConstruction() {
    val base = SwiftTUIWebSurfaceSession().decode(full(80, 24))!!
    val next = base.copy(consumedGeneration = 2, dirtyRows = (0 until 24).toList(),
      requiresFullTextRepaint = false, requiresFullGraphicsReplay = false)
    val plan = SwiftTUIDamagePlan.plan(next, 1, false)
    var selected = 0
    assertEquals(1920, SwiftTUIDamagePlan.forEachDamagedCell(next, plan) { selected++ })
    assertEquals(1920, selected)
  }

  @Test
  fun invalidDimensionsDoNotReplaceTheDeltaBaseline() {
    for (invalid in listOf("-1", "1.5", "2147483648", "1e100", "null", "\"1\"")) {
      for (axis in listOf("width", "height")) {
        val session = SwiftTUIWebSurfaceSession()
        session.decode(full(1, 1))
        val malformedFull = full(1, 1).replace("\"$axis\":1", "\"$axis\":$invalid")
        assertThrows(IllegalArgumentException::class.java) { session.decode(malformedFull) }
        val delta = prefix + """{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":1,"height":1,"styles":[null],"deltaRows":[]}"""
        assertThrows(IllegalArgumentException::class.java) {
          session.decode(delta.replace("\"$axis\":1", "\"$axis\":$invalid"))
        }
        assertEquals(2L, session.decode(delta)!!.consumedGeneration)
      }
    }
    val empty = SwiftTUIWebSurfaceSession().decode(full(0, 0))!!
    assertEquals(0, empty.gridWidth)
    assertEquals(0, empty.gridHeight)
  }
}
