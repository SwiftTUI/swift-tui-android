package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1InputTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (16..30).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    fun bytes(expected: String, actual: ByteArray) = assertArrayEquals(id, expected.encodeToByteArray(), actual)
    when (scenario) {
      16 -> {
        assertEquals(1, SwiftTUIInput.cellColumn(-9f, 8f))
        assertEquals(1, SwiftTUIInput.cellRow(-3f, 16f))
      }
      17 -> {
        assertEquals(2, SwiftTUIInput.cellColumn(15.9f, 8f))
        assertEquals(3, SwiftTUIInput.cellColumn(16f, 8f))
      }
      18 -> assertEquals(3, SwiftTUIInput.cellRow(2f, 0f))
      19 -> bytes("\u001B[<0;2;3M", SwiftTUIInput.mouseDown(2, 3))
      20 -> bytes("\u001B[<0;2;3m", SwiftTUIInput.mouseUp(2, 3))
      21 -> bytes("\u001B[<60;2;3M", SwiftTUIInput.mouseDrag(2, 3, 28))
      22 -> bytes("\u001B[<0;1;1M", SwiftTUIInput.mouseDown(0, -1))
      23 -> bytes("", SwiftTUIInput.verticalScroll(1, 1, 0))
      24 -> bytes("\u001B[<64;1;1M\u001B[<64;1;1M", SwiftTUIInput.verticalScroll(1, 1, 2))
      25 -> bytes("\u001B[<65;1;1M\u001B[<65;1;1M", SwiftTUIInput.verticalScroll(1, 1, -2))
      26 -> bytes("\u001B[<64;1;1M\u001B[<64;1;1M", SwiftTUIInput.verticalScroll(1, 1, 5, 2))
      27 -> bytes("\u001B[200~abc\u001B[201~", SwiftTUIInput.bracketedPaste("a\u0000b\u001Bc\u007F"))
      28 -> bytes("\u001B[200~界😀\u001B[201~", SwiftTUIInput.bracketedPaste("界😀"))
      29 -> bytes("", SwiftTUIInput.bracketedPaste("\u0000\u001B\u007F"))
      30 -> bytes("\u001B[200~a\tb\nc\u001B[201~", SwiftTUIInput.bracketedPaste("a\tb\nc"))
      else -> error(id)
    }
  }
}
