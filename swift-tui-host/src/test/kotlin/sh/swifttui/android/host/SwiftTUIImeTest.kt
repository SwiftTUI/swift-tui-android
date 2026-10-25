package sh.swifttui.android.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SwiftTUIImeTest {
  @Test
  fun appendedTextEmitsTheSuffix() {
    assertEquals("i", SwiftTUIIme.bytesForEdit("h", "hi").decodeToString())
    assertEquals("ello", SwiftTUIIme.bytesForEdit("h", "hello").decodeToString())
  }

  @Test
  fun deletionEmitsBackspaces() {
    assertArrayEquals(
      byteArrayOf(0x7F),
      SwiftTUIIme.bytesForEdit("hi", "h")
    )
    assertArrayEquals(
      byteArrayOf(0x7F, 0x7F),
      SwiftTUIIme.bytesForEdit("hello", "hel")
    )
  }

  @Test
  fun replacementBackspacesThenInserts() {
    // "cat" -> "cot": delete 't','a' down to common prefix "c", then insert "ot".
    assertArrayEquals(
      byteArrayOf(0x7F, 0x7F) + "ot".encodeToByteArray(),
      SwiftTUIIme.bytesForEdit("cat", "cot")
    )
  }

  @Test
  fun newlinesAreNormalizedToCarriageReturn() {
    assertEquals("\r", SwiftTUIIme.bytesForEdit("", "\n").decodeToString())
    assertEquals("a\r", SwiftTUIIme.bytesForEdit("", "a\r\n").decodeToString())
  }

  @Test
  fun noChangeEmitsNothing() {
    assertArrayEquals(ByteArray(0), SwiftTUIIme.bytesForEdit("same", "same"))
    assertArrayEquals(ByteArray(0), SwiftTUIIme.bytesForEdit("", ""))
  }

  // The runtime's DEL removes one Swift Character, an extended grapheme
  // cluster, not one UTF-16 unit (STUI-1183).

  @Test
  fun deletionRemovesOneDelPerCharacterNotPerUtf16Unit() {
    assertEdits(
      Edit("middle emoji", "a${GRINNING}b", "ab", "$DEL${DEL}b"),
      Edit("skin-tone modifier", "A$THUMBS_UP$MEDIUM_SKIN", "A", DEL),
      // CRLF is one character, and it reached the runtime as one CR.
      Edit("CRLF", "a\r\n", "a", DEL),
      // Controls: UTF-16 and character counts already agree.
      Edit("LF", "a\n", "a", DEL),
      Edit("ASCII after emoji", "A${GRINNING}b", "A$GRINNING", DEL)
    )
  }

  @Test
  fun replacementResendsTheWholeEditedCharacter() {
    assertEdits(
      Edit("second regional indicator", "A$FLAG_CA", "A$FLAG_CN", DEL + FLAG_CN),
      Edit("last ZWJ component", "A$TECHNOLOGIST", "A$SCIENTIST", DEL + SCIENTIST)
    )
  }

  @Test
  fun appendsThatExtendTheLastCharacterSendOnlyTheSuffix() {
    assertEdits(
      Edit("emoji", "A", "A$GRINNING", GRINNING),
      Edit("flag completion", "A$RI_C", "A$FLAG_CA", RI_A),
      Edit("ZWJ continuation", "A$WOMAN", "A$TECHNOLOGIST", "$ZWJ$LAPTOP"),
      Edit("combining mark", "e", "é", "́")
    )
  }

  // The host keeps its IME buffer with every line break as one LF, the newline
  // the runtime inserted for the one CR it received (STUI-1251).

  @Test
  fun normalizedBufferKeepsEachLineBreakAsOneLineFeed() {
    assertEquals("a\nb\nc\n", SwiftTUIIme.normalizedBuffer("a\r\nb\rc\n"))
    val plain = "plain\ntext"
    assertSame(plain, SwiftTUIIme.normalizedBuffer(plain))
  }

  @Test
  fun normalizedBufferOffsetsKeepTheirPosition() {
    // Between CR and LF maps to before the LF that replaces the pair.
    assertEquals(
      listOf(0, 1, 1, 2, 3),
      (0..4).map { SwiftTUIIme.normalizedBufferOffset("a\r\nb", it) }
    )
    assertEquals(
      listOf(0, 1, 2, 3),
      (0..3).map { SwiftTUIIme.normalizedBufferOffset("a\rb", it) }
    )
  }

  private class Edit(val label: String, val old: String, val new: String, val expected: String)

  /** Compares every edit's bytes at once, so a failure reports each actual value. */
  private fun assertEdits(vararg edits: Edit) {
    fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
    assertEquals(
      edits.map { "${it.label}: ${hex(it.expected.encodeToByteArray())}" },
      edits.map { "${it.label}: ${hex(SwiftTUIIme.bytesForEdit(it.old, it.new))}" }
    )
  }

  private companion object {
    const val DEL = "\u007F"
    const val GRINNING = "😀" // U+1F600
    const val THUMBS_UP = "👍" // U+1F44D
    const val MEDIUM_SKIN = "🏽" // U+1F3FD
    const val RI_C = "🇨" // U+1F1E8
    const val RI_A = "🇦" // U+1F1E6
    const val FLAG_CA = RI_C + RI_A
    const val FLAG_CN = RI_C + "🇳" // U+1F1E8 U+1F1F3
    const val WOMAN = "👩" // U+1F469
    const val ZWJ = "‍"
    const val LAPTOP = "💻" // U+1F4BB
    const val TECHNOLOGIST = WOMAN + ZWJ + LAPTOP
    const val SCIENTIST = WOMAN + ZWJ + "🔬" // U+1F52C
  }
}
