package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1DamageTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (31..45).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    fun plan(frame: SwiftTUIFrame = a1Frame(), previous: Long = 2, resized: Boolean = false) =
      SwiftTUIDamagePlan.plan(frame, previous, resized)
    fun damaged(vararg ranges: SwiftTUIRange) = a1Frame(damage = listOf(SwiftTUITextDamageRow(0, ranges.toList())))
    fun columns(frame: SwiftTUIFrame) = plan(frame).also { assertFalse(it.fullRepaint) }.rows.single().columnRanges
    when (scenario) {
      31 -> assertTrue(plan(previous = -1).fullRepaint)
      32 -> assertTrue(plan(resized = true).fullRepaint)
      33 -> assertTrue(plan(a1Frame(generation = 4)).fullRepaint)
      34 -> assertFalse(plan(a1Frame(sequence = 99)).fullRepaint)
      35 -> assertTrue(plan(a1Frame(fullText = true)).fullRepaint)
      36 -> assertTrue(plan(a1Frame(fullGraphics = true)).fullRepaint)
      37 -> assertTrue(plan(a1Frame(cells = emptyList())).fullRepaint)
      38 -> assertTrue(plan(a1Frame(images = listOf(a1Image()))).fullRepaint)
      39 -> assertEquals(listOf(1..5), columns(damaged(SwiftTUIRange(1, 4), SwiftTUIRange(3, 6))))
      40 -> assertEquals(listOf(1..4), columns(damaged(SwiftTUIRange(1, 3), SwiftTUIRange(3, 5))))
      41 -> assertEquals(listOf(0..1, 5..6), columns(damaged(SwiftTUIRange(5, 7), SwiftTUIRange(0, 2))))
      42 -> assertEquals(listOf(0..1, 7..7), columns(damaged(SwiftTUIRange(-2, 2), SwiftTUIRange(7, 12))))
      43 -> assertEquals(listOf(0..7), columns(damaged()))
      44 -> assertEquals(listOf(2..2), columns(damaged(SwiftTUIRange(2, 3)).copy(dirtyRows = listOf(0))))
      45 -> {
        val lead = a1Cell(1, 0, 2)
        val frame = a1Frame(cells = listOf(lead, a1Cell(2, 0, continuation = 1), a1Cell(0, 1)),
          damage = listOf(SwiftTUITextDamageRow(0, listOf(SwiftTUIRange(2, 3)))))
        val painted = mutableListOf<SwiftTUICell>()
        assertEquals(2, SwiftTUIDamagePlan.forEachDamagedCell(frame, plan(frame)) { painted += it })
        assertEquals(listOf(lead), painted)
      }
      else -> error(id)
    }
  }
}
