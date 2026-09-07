package sh.swifttui.android.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Deterministic clip-command/source-over oracle. This interprets the geometry
 * consumed by Canvas; it does not claim Android font or device pixel coverage.
 */
class SwiftTUIRasterClipGeometryTest {
  private val prefix = SwiftTUIWebSurfaceSession.RECORD_PREFIX

  private fun full(row: String, styles: String = "[null]"): String = prefix +
    """{"version":2,"epoch":7,"gen":1,"width":4,"height":1,"styles":$styles,"rows":[$row]}"""

  private fun delta(row: String, ranges: String, styles: String = "[null]"): String = prefix +
    """{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":4,"height":1,"styles":$styles,"deltaRows":[[0,$row]],"damage":{"textRows":[[0,$ranges]],"requiresFullTextRepaint":false,"requiresFullGraphicsReplay":false}}"""

  @Test
  fun continuationOnlyDamageDoesNotCompositeTheCleanHalfOfATranslucentWideCell() {
    val session = SwiftTUIWebSurfaceSession()
    val row = """[[0," ",2,0],[2," ",2,0]]"""
    val styles = """[{"bg":"#ffffffff","opacity":0.5}]"""
    val first = session.decode(full(row, styles))!!
    val next = session.decode(delta(row, "[[1,2]]", styles))!!
    val plan = SwiftTUIDamagePlan.plan(next, first.consumedGeneration, false)
    val selected = mutableListOf<SwiftTUICell>()
    SwiftTUIDamagePlan.forEachDamagedCell(next, plan, selected::add)
    assertEquals(listOf(0), selected.map { it.x })
    val initial = render(first.cells)
    val incremental = render(selected, initial.copyOf(), SwiftTUIRasterClipGeometry.damage(plan))
    assertArrayEquals(initial, incremental, 0.0)
    assertEquals(0.5, incremental[0], 0.0)
    // The previous painter cleared the continuation but then composited the
    // whole lead span. This witness confirms that the oracle detects it.
    val unscoped = initial.copyOf()
    for (index in unscoped.indices) if (index / SAMPLES_PER_CELL == 1) unscoped[index] = 0.0
    paint(selected, unscoped, null)
    assertEquals(0.75, unscoped[0], 0.0)
  }

  @Test
  fun disjointDamageCommandsKeepTheUnchangedHoleOutOfTheClip() {
    val session = SwiftTUIWebSurfaceSession()
    val first = session.decode(full("""[[0," ",4,0]]"""))!!
    val next = session.decode(delta("""[[0," ",4,0]]""", "[[0,1],[2,3]]"))!!
    val clips = SwiftTUIRasterClipGeometry.damage(
      SwiftTUIDamagePlan.plan(next, first.consumedGeneration, false)
    )
    assertEquals(listOf(SwiftTUIRect(0, 0, 1, 1), SwiftTUIRect(2, 0, 1, 1)), clips)
    assertFalse(contains(clips, 1.5, 0.5))
    assertFalse(contains(clips, 0.5, 1.5))
  }

  @Test
  fun changedAndRemovedItalicOverhangMasksMatchAFullRepaint() {
    for (replacement in listOf("I", " ")) {
      val session = SwiftTUIWebSurfaceSession()
      val first = session.decode(full("""[[1,"W",1,0]]"""))!!
      val next = session.decode(delta("""[[1,"$replacement",1,0]]""", "[[1,2]]"))!!
      val initial = render(first.cells)
      val selected = mutableListOf<SwiftTUICell>()
      val plan = SwiftTUIDamagePlan.plan(next, first.consumedGeneration, false)
      SwiftTUIDamagePlan.forEachDamagedCell(next, plan, selected::add)
      val incremental = render(selected, initial.copyOf(), SwiftTUIRasterClipGeometry.damage(plan))
      assertArrayEquals(render(next.cells), incremental, 0.0)
      assertEquals(0.0, initial[2 * SAMPLES_PER_CELL], 0.0)
    }
  }

  @Test
  fun cellClipCoversTheDeclaredWideSpanAtItsOwnRow() {
    val cell = SwiftTUICell(3, 2, "界", 2, null, null, null)
    assertEquals(SwiftTUIRect(3, 2, 2, 1), SwiftTUIRasterClipGeometry.cell(cell))
  }

  private fun render(
    cells: List<SwiftTUICell>,
    pixels: DoubleArray = DoubleArray(4 * SAMPLES_PER_CELL),
    damage: List<SwiftTUIRect>? = null
  ): DoubleArray {
    for (index in pixels.indices) {
      if (damage == null || contains(damage, sampleX(index), 0.5)) pixels[index] = 0.0
    }
    paint(cells, pixels, damage)
    return pixels
  }

  private fun paint(cells: List<SwiftTUICell>, pixels: DoubleArray, damage: List<SwiftTUIRect>?) {
    for (cell in cells) {
      val span = SwiftTUIRasterClipGeometry.cell(cell)
      for (index in pixels.indices) {
        val x = sampleX(index)
        if (damage != null && !contains(damage, x, 0.5)) continue
        if (!contains(listOf(span), x, 0.5)) continue
        if (cell.style?.backgroundColor != null) {
          val alpha = cell.style.opacity
          pixels[index] = alpha + (1 - alpha) * pixels[index]
        }
        // Deliberately overhanging W and narrow I coverage masks model font
        // ink independently of the production cell clip geometry.
        val glyphInk = when (cell.character) {
          "W" -> x >= cell.x && x < cell.x + 1.2
          "I" -> x >= cell.x + 0.25 && x < cell.x + 0.45
          else -> false
        }
        if (glyphInk) pixels[index] = 1.0
      }
    }
  }

  private fun contains(rects: List<SwiftTUIRect>, x: Double, y: Double): Boolean = rects.any {
    x >= it.x && x < it.x.toDouble() + it.width && y >= it.y && y < it.y.toDouble() + it.height
  }

  private fun sampleX(index: Int): Double = (index + 0.5) / SAMPLES_PER_CELL

  private companion object {
    const val SAMPLES_PER_CELL = 10
  }
}
