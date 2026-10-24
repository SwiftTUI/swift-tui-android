package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1ClipTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (46..55).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    fun damage(vararg ranges: IntRange, row: Int = 0) = SwiftTUIRasterClipGeometry.damage(
      SwiftTUIDamagePlan.Plan(false, listOf(SwiftTUIDamagePlan.RowDamage(row, ranges.toList())))
    )
    when (scenario) {
      46 -> assertTrue(SwiftTUIRasterClipGeometry.damage(SwiftTUIDamagePlan.Plan(false, emptyList())).isEmpty())
      47 -> assertEquals(listOf(SwiftTUIRect(1, 2, 3, 1)), damage(1..3, row = 2))
      48 -> assertEquals(listOf(SwiftTUIRect(0, 0, 2, 1), SwiftTUIRect(4, 0, 1, 1)), damage(0..1, 4..4))
      49 -> assertTrue(damage(1 until 1).isEmpty())
      50 -> assertEquals(SwiftTUIRect(2, 3, 2, 1), SwiftTUIRasterClipGeometry.cell(a1Cell(2, 3, 2)))
      51 -> assertEquals(SwiftTUIRect(0, 0, 1, 1), SwiftTUIRasterClipGeometry.cell(a1Cell(span = 0)))
      52 -> assertEquals(7.5f, SwiftTUIRasterClipGeometry.pixelEnd(3, 2, 1.5f), 0f)
      53 -> assertEquals(4f, SwiftTUIRasterClipGeometry.pixelEnd(-3, 5, 2f), 0f)
      54 -> assertEquals(2147483648f, SwiftTUIRasterClipGeometry.pixelEnd(Int.MAX_VALUE, 1, 1f), 0f)
      55 -> assertEquals(-2f, SwiftTUIRasterClipGeometry.pixelEnd(Int.MIN_VALUE, Int.MAX_VALUE, 2f), 0f)
      else -> error(id)
    }
  }
}
