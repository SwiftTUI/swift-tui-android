package sh.swifttui.android.host

import java.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wave-3 regression hunt, part CB (STUI-1167): neighbours of the STUI-1251
 * LF-normalized IME buffer. The coordination root's wave-3 hunt evidence owns
 * the case descriptions (w3cb016 to w3cb020).
 *
 * Each case edits through [ImeHost], the buffer `SwiftTUIHostView` keeps
 * (diffed with [SwiftTUIIme.bytesForEdit], stored as
 * [SwiftTUIIme.normalizedBuffer]), into [RuntimeEditor], a model of the
 * runtime's multi-line editor: DEL deletes one character, every CR is one
 * Return (a newline), other UTF-8 appends.
 */
class Wave3HuntCbImeTest {
  @Test
  fun w3cb016CarriageReturnThenCrlfInOneEditKeepsTwoLineBreaksInStep() {
    val host = ImeHost()
    val paste = host.commit("a\r\r\n")
    val steps = mutableListOf(host.editor.text to host.buffer)
    repeat(3) {
      host.deleteLastCharacter()
      steps += host.editor.text to host.buffer
    }
    assertEquals(
      "61 0D 0D" to listOf("a\n\n" to "a\n\n", "a\n" to "a\n", "a" to "a", "" to ""),
      hex(paste) to steps
    )
  }

  @Test
  fun w3cb017LineFeedThenALoneCarriageReturnAreTwoLineBreaks() {
    val host = ImeHost()
    val paste = host.commit("a\n\r")
    val afterPaste = host.editor.text to host.buffer
    host.deleteLastCharacter()
    assertEquals(
      Triple("61 0D 0D", "a\n\n" to "a\n\n", "a\n" to "a\n"),
      Triple(hex(paste), afterPaste, host.editor.text to host.buffer)
    )
  }

  @Test
  fun w3cb018EveryOffsetOfAMixedBufferMapsToTheNormalizedPrefix() {
    val text = "a\r\nb\rc\r\r\nd\n\r"
    val expected = (0..text.length).map { offset ->
      val betweenCrAndLf =
        offset in 1 until text.length && text[offset - 1] == '\r' && text[offset] == '\n'
      SwiftTUIIme.normalizedBuffer(text.substring(0, if (betweenCrAndLf) offset - 1 else offset)).length
    }
    val observed = (0..text.length).map { SwiftTUIIme.normalizedBufferOffset(text, it) }
    assertEquals(expected, observed)
    assertEquals(SwiftTUIIme.normalizedBuffer(text).length, observed.last())
  }

  @Test
  fun w3cb019MidBufferInsertionAfterASplitCarriageReturnHistoryStaysInStep() {
    val host = ImeHost()
    host.commit("a\r")
    host.commit(host.buffer + "b")
    val insertion = host.commit("ax\nb")
    assertEquals(
      Triple("7F 7F 78 0D 62", "ax\nb", "ax\nb"),
      Triple(hex(insertion), host.editor.text, host.buffer)
    )
  }

  @Test
  fun w3cb020WholeBufferCrlfPasteReplacementStaysInStep() {
    val host = ImeHost()
    host.commit("a\r\nb")
    val replacement = host.commit("c\r\nd\r")
    val afterReplacement = host.editor.text to host.buffer
    host.deleteLastCharacter()
    assertEquals(
      Triple("7F 7F 7F 63 0D 64 0D", "c\nd\n" to "c\nd\n", "c\nd" to "c\nd"),
      Triple(hex(replacement), afterReplacement, host.editor.text to host.buffer)
    )
  }

  /**
   * The host's invisible IME buffer, as `SwiftTUIHostView` keeps it: each IME
   * edit is diffed against the buffer held, its bytes reach [editor], and the
   * buffer kept is the edited one, normalized.
   */
  private class ImeHost {
    val editor = RuntimeEditor()
    var buffer = ""
      private set

    /** Applies the IME's next buffer and returns the bytes sent. */
    fun commit(next: String): ByteArray {
      val bytes = SwiftTUIIme.bytesForEdit(buffer, next)
      editor.receive(bytes)
      buffer = SwiftTUIIme.normalizedBuffer(next)
      return bytes
    }

    /** The IME deleting the last character (grapheme cluster) of the buffer. */
    fun deleteLastCharacter(): ByteArray {
      val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
      boundaries.setText(buffer)
      return commit(buffer.substring(0, boundaries.preceding(buffer.length)))
    }
  }

  /** The runtime's multi-line editor fed by [SwiftTUIIme.bytesForEdit]. */
  private class RuntimeEditor {
    var text = ""
      private set

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
        // Each CR or LF byte is one Return; a multi-line editor inserts a newline.
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
    const val DEL_BYTE = 0x7F.toByte()

    fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
  }
}
