package sh.swifttui.android.host

import java.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wave-2 regression hunt, part CB (STUI-1165): neighbours of the STUI-1183
 * grapheme-cluster IME diff. The coordination root's wave-2 hunt evidence owns
 * the case descriptions (w2cb009 to w2cb012).
 *
 * Each case replays IME buffer edits into [RuntimeEditor], a model of the
 * runtime's multi-line text editor: DEL deletes one character, every CR is one
 * Return (a newline), and other UTF-8 appends. w2cb009 and the wave-3 cases
 * (STUI-1251) edit through [ImeHost], the buffer the host keeps.
 */
class Wave2HuntCbImeTest {
  @Test
  fun w2cb009CarriageReturnAndLineFeedFromSeparateEditsDeleteToTheBuffer() {
    // A paste ending in CR, then Enter: the runtime receives two Returns.
    // STUI-1251 (fixed in wave 3): the host keeps them as two LF characters,
    // so deleting both sends two DELs. The hunt replayed the raw buffers
    // "a\r" and "a\r\n", where CR and LF merge into one character; the IME
    // now edits the buffer the host keeps, so Enter appends to "a\n".
    val host = ImeHost()
    host.commit("a\r")
    host.commit(host.buffer + "\n")
    val afterEnter = host.editor.text
    val bufferAfterEnter = host.buffer
    val lastEdit = host.commit("a")
    assertEquals(
      "w2cb009",
      listOf("a\n\n", "a\n\n", "7F 7F", "a"),
      listOf(afterEnter, bufferAfterEnter, hex(lastEdit), host.editor.text)
    )
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

  // Wave 3 repair scenarios for STUI-1251: the reverse histories, each
  // replayed through the host's buffer.

  @Test
  fun w3cbA01DeletionInsideASingleCommitCrlfSendsNoReturn() {
    // (a) A CRLF pasted in one edit is one Return, kept as one LF; the IME
    // deleting the one UTF-16 unit before the cursor removes it with one DEL.
    val a = ImeHost()
    val pasteA = a.commit("a\r\n")
    val bufferA = a.buffer
    val deleteA = a.commit(a.buffer.dropLast(1))
    // (b) The unit before "b" in a pasted "a\r\nb" is the whole line break.
    val b = ImeHost()
    b.commit("a\r\nb")
    val deleteB = b.commit(b.buffer.removeRange(b.buffer.length - 2, b.buffer.length - 1))
    assertEquals(
      listOf("61 0D", "a\n", "7F", "a", "a", "7F 7F 62", "ab", "ab"),
      listOf(
        hex(pasteA),
        bufferA,
        hex(deleteA),
        a.editor.text,
        a.buffer,
        hex(deleteB),
        b.editor.text,
        b.buffer
      )
    )
  }

  @Test
  fun w3cbA02CarriageReturnThenLineFeedThenACharacterStayInStep() {
    val host = ImeHost()
    val edits = listOf<(String) -> String>({ "a\r" }, { it + "\n" }, { it + "b" })
    val observed = edits.map { edit ->
      host.commit(edit(host.buffer))
      host.editor.text to normalizedNewlines(host.buffer)
    }
    assertEquals(
      listOf("a\n" to "a\n", "a\n\n" to "a\n\n", "a\n\nb" to "a\n\nb"),
      observed
    )
  }

  @Test
  fun w3cbA04PastedCrlfHistoryThroughTheHostStaysInStep() {
    // The w2cb011 history, each edit made to the buffer the host keeps.
    val host = ImeHost()
    val observed = mutableListOf<Pair<String, String>>()
    fun record() {
      observed += host.editor.text to normalizedNewlines(host.buffer)
    }
    host.commit("x\r\ny")
    record()
    host.deleteLastCharacter()
    record()
    host.deleteLastCharacter()
    record()
    host.commit(host.buffer + WOMAN)
    record()
    host.commit(host.buffer + ZWJ + LAPTOP)
    record()
    host.deleteLastCharacter()
    record()
    host.deleteLastCharacter()
    record()
    val expected = listOf("x\ny", "x\n", "x", "x$WOMAN", "x$WOMAN$ZWJ$LAPTOP", "x", "")
    assertEquals(expected.map { it to it }, observed)
  }

  private class Edit(val label: String, val old: String, val new: String, val expected: String)

  /**
   * Compares every edit's bytes, and the runtime text after replaying the old
   * buffer and then the edit, at once, so a failure reports each actual value.
   */
  private fun assertEdits(vararg edits: Edit) {
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

    fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
  }
}
