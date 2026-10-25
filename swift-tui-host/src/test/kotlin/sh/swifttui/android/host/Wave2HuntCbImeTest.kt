package sh.swifttui.android.host

import java.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Wave-2 regression hunt, part CB (STUI-1165): neighbours of the STUI-1183
 * grapheme-cluster IME diff. The coordination root's wave-2 hunt evidence owns
 * the case descriptions (w2cb009 to w2cb012).
 *
 * Each case replays IME buffer edits into [RuntimeEditor], a model of the
 * runtime's multi-line text editor: DEL deletes one character, every CR is one
 * Return (a newline), and other UTF-8 appends.
 */
class Wave2HuntCbImeTest {
  private val unmarked = System.getenv("SWIFTTUI_W2HUNT_UNMARKED") == "1"

  @Test
  fun w2cb009CarriageReturnAndLineFeedFromSeparateEditsDeleteToTheBuffer() {
    val editor = RuntimeEditor()
    // A paste ending in CR, then Enter: the runtime receives two Returns while
    // the buffer's CR and LF merge into one character.
    editor.replay("", "a\r", "a\r\n")
    assertEquals("a\n\n", editor.text)
    val lastEdit = SwiftTUIIme.bytesForEdit("a\r\n", "a")
    editor.receive(lastEdit)
    if (unmarked) {
      assertEquals("w2cb009", "a", editor.text)
    } else {
      // NEW-2: bytesForEdit counts characters of the IME buffer, where a CR
      // and an LF from separate edits merge into one character, though the
      // runtime received two Returns (adjacent STUI-1183). Qualify the exact
      // observed output before guarding only the contract.
      assertArrayEquals("w2cb009 exact known bytes", byteArrayOf(0x7F), lastEdit)
      assertEquals("w2cb009 exact known output", "a\n", editor.text)
      assertThrows(AssertionError::class.java) {
        assertEquals("w2cb009", "a", editor.text)
      }
      println(
        "W2HUNT_KNOWN_ASSERTION w2cb009 NEW-2: deleting a CR LF committed in separate edits " +
          "sends one DEL and leaves a newline; the diff counts the merged buffer character; " +
          "contract: SwiftTUIIme.bytesForEdit leaves the runtime reducer in the same state; " +
          "adjacent STUI-1183"
      )
    }
  }

  @Test
  fun w2cb010CodePointDeletedInsideACharacterResendsTheRest() {
    assertEdits(
      Edit("combining mark", "Ae\u0301", "Ae", "${DEL}e"),
      Edit("Devanagari vowel sign", "a\u0915\u093F", "a\u0915", "${DEL}\u0915"),
      Edit("Hangul trailing jamo", "\u1100\u1161\u11A8", "\u1100\u1161", "${DEL}\u1100\u1161")
    )
  }

  @Test
  fun w2cb011PastedCrlfHistoryStaysInStep() {
    val editor = RuntimeEditor()
    var buffer = ""
    val history = listOf(
      "x\r\ny",
      "x\r\n",
      "x",
      "x$WOMAN",
      "x$WOMAN$ZWJ$LAPTOP",
      "x",
      ""
    )
    val observed = history.map { next ->
      editor.receive(SwiftTUIIme.bytesForEdit(buffer, next))
      buffer = next
      editor.text
    }
    assertEquals(history.map(::normalizedNewlines), observed)
  }

  @Test
  fun w2cb012EditsThatMoveACharacterBoundaryKeepTheRuntimeInStep() {
    assertEdits(
      Edit("combining mark inserted mid-buffer", "ab", "a\u0301b", "${DEL}\u0301b"),
      Edit("odd regional indicator appended", "A$RI_C$RI_A", "A$RI_C$RI_A$RI_U", RI_U),
      Edit(
        "last regional indicator deleted",
        "A$RI_C$RI_A$RI_U$RI_S",
        "A$RI_C$RI_A$RI_U",
        DEL + RI_U
      ),
      Edit(
        "leading regional indicator deleted",
        "$RI_C$RI_A$RI_U$RI_S",
        "$RI_A$RI_U$RI_S",
        "$DEL$DEL$RI_A$RI_U$RI_S"
      )
    )
  }

  private class Edit(val label: String, val old: String, val new: String, val expected: String)

  /**
   * Compares every edit's bytes, and the runtime text after replaying the old
   * buffer and then the edit, at once, so a failure reports each actual value.
   */
  private fun assertEdits(vararg edits: Edit) {
    fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
    assertEquals(
      edits.map { "${it.label}: ${hex(it.expected.encodeToByteArray())} -> ${it.new}" },
      edits.map {
        val editor = RuntimeEditor()
        editor.replay("", it.old)
        val bytes = SwiftTUIIme.bytesForEdit(it.old, it.new)
        editor.receive(bytes)
        "${it.label}: ${hex(bytes)} -> ${editor.text}"
      }
    )
  }

  /** The runtime's multi-line editor fed by [SwiftTUIIme.bytesForEdit]. */
  private class RuntimeEditor {
    var text = ""
      private set

    fun replay(vararg buffers: String) {
      for (index in 1 until buffers.size) {
        receive(SwiftTUIIme.bytesForEdit(buffers[index - 1], buffers[index]))
      }
    }

    fun receive(bytes: ByteArray) {
      var start = 0
      for (index in bytes.indices) {
        if (bytes[index] == DEL_BYTE) {
          append(bytes, start, index)
          deleteBackward()
          start = index + 1
        }
      }
      append(bytes, start, bytes.size)
    }

    private fun append(bytes: ByteArray, from: Int, to: Int) {
      if (to > from) {
        // Each CR byte is one Return; a multi-line editor inserts a newline.
        text += bytes.decodeToString(from, to).replace('\r', '\n')
      }
    }

    private fun deleteBackward() {
      if (text.isEmpty()) return
      val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
      boundaries.setText(text)
      text = text.substring(0, boundaries.preceding(text.length))
    }
  }

  private companion object {
    const val DEL = "\u007F"
    const val DEL_BYTE = 0x7F.toByte()
    const val WOMAN = "👩" // U+1F469
    const val ZWJ = "\u200D"
    const val LAPTOP = "💻" // U+1F4BB
    const val RI_C = "🇨" // U+1F1E8
    const val RI_A = "🇦" // U+1F1E6
    const val RI_U = "🇺" // U+1F1FA
    const val RI_S = "🇸" // U+1F1F8

    fun normalizedNewlines(buffer: String): String =
      buffer.replace("\r\n", "\n").replace('\r', '\n')
  }
}
