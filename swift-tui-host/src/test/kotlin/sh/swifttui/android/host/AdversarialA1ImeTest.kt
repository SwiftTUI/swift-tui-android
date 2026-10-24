package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1ImeTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (1..15).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    val (old, new, expected) = when (scenario) {
      1 -> Triple("a", "ab", "b")
      2 -> Triple("cat", "cot", "\u007F\u007Fot")
      3 -> Triple("abc", "a", "\u007F\u007F")
      4 -> Triple("same", "same", "")
      5 -> Triple("A界", "A", "\u007F")
      6 -> Triple("A😀", "A", "\u007F")
      7 -> Triple("A🇨🇦", "A", "\u007F")
      8 -> Triple("Ae\u0301", "A", "\u007F")
      9 -> Triple("A👩‍💻", "A", "\u007F")
      10 -> Triple("A😀", "A😁", "\u007F😁")
      11 -> Triple("Ae\u0301", "Ae\u0300", "\u007Fe\u0300")
      12 -> Triple("e", "e\u0301", "\u0301")
      13 -> Triple("a", "a\r\n", "\r")
      14 -> Triple("", "a\nb\r\nc", "a\rb\rc")
      15 -> Triple("abcd", "acd", "\u007F\u007F\u007Fcd")
      else -> error(id)
    }
    assertArrayEquals(id, expected.encodeToByteArray(), SwiftTUIIme.bytesForEdit(old, new))
  }
}
