package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1BudgetTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (66..75).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    when (scenario) {
      66 -> assertTrue(SwiftTUIWireBudget.grid(0, 0))
      67 -> assertTrue(SwiftTUIWireBudget.grid(0, 1024))
      68 -> assertTrue(SwiftTUIWireBudget.grid(256, 256))
      69 -> assertFalse(SwiftTUIWireBudget.grid(257, 256))
      70 -> assertTrue(SwiftTUIWireBudget.imageSize(4096, 4096))
      71 -> assertFalse(SwiftTUIWireBudget.imageSize(4097, 4096))
      72, 73, 74 -> {
        val value = when (scenario) { 72 -> "abc"; 73 -> "界"; else -> "😀" }
        val limit = if (scenario == 74) 4 else 3
        assertTrue(SwiftTUIWireBudget.fitsUTF8(value, limit))
        assertFalse(SwiftTUIWireBudget.fitsUTF8(value, limit - 1))
      }
      75 -> assertTrue(SwiftTUIWireBudget.admitsJSON("{\"note\":\"[{}] \\\" [\"}"))
      else -> error(id)
    }
  }
}
