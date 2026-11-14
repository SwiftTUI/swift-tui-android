package sh.swifttui.android.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ESC = ""

class SwiftTUIInputTest {
  @Test
  fun cellCoordinatesAreOneBased() {
    assertEquals(1, SwiftTUIInput.cellColumn(0f, 9f))
    assertEquals(1, SwiftTUIInput.cellColumn(8.9f, 9f))
    assertEquals(2, SwiftTUIInput.cellColumn(9f, 9f))
    assertEquals(1, SwiftTUIInput.cellRow(0f, 18f))
    assertEquals(3, SwiftTUIInput.cellRow(36f, 18f))
  }

  @Test
  fun mousePressAndReleaseUseSgrButtonZero() {
    assertEquals("$ESC[<0;3;5M", SwiftTUIInput.mouseDown(3, 5).decodeToString())
    assertEquals("$ESC[<0;3;5m", SwiftTUIInput.mouseUp(3, 5).decodeToString())
  }

  @Test
  fun dragSetsTheMotionFlag() {
    assertEquals("$ESC[<32;4;6M", SwiftTUIInput.mouseDrag(4, 6).decodeToString())
  }

  @Test
  fun wheelEncodesDirection() {
    assertEquals("$ESC[<64;2;2M", SwiftTUIInput.wheel(2, 2, up = true).decodeToString())
    assertEquals("$ESC[<65;2;2M", SwiftTUIInput.wheel(2, 2, up = false).decodeToString())
  }

  @Test
  fun modifiersAreOredIntoTheButton() {
    // 0 | shift(4) | ctrl(16) = 20
    assertEquals(
      "$ESC[<20;1;1M",
      SwiftTUIInput.mouseDown(
        1,
        1,
        modifiers = SwiftTUIInput.MODIFIER_SHIFT or SwiftTUIInput.MODIFIER_CTRL
      ).decodeToString()
    )
  }

  @Test
  fun verticalScrollEmitsOneNotchPerLineInTheRightDirection() {
    assertEquals(
      "$ESC[<64;1;1M$ESC[<64;1;1M",
      SwiftTUIInput.verticalScroll(1, 1, deltaLines = 2).decodeToString()
    )
    assertEquals(
      "$ESC[<65;1;1M$ESC[<65;1;1M$ESC[<65;1;1M",
      SwiftTUIInput.verticalScroll(1, 1, deltaLines = -3).decodeToString()
    )
  }

  @Test
  fun verticalScrollIsEmptyForZeroAndClampsLargeFlings() {
    assertArrayEquals(ByteArray(0), SwiftTUIInput.verticalScroll(1, 1, deltaLines = 0))
    val clamped = SwiftTUIInput.verticalScroll(1, 1, deltaLines = 100, maxNotches = 4)
    val notches = clamped.decodeToString().split("M").count { it.isNotEmpty() }
    assertEquals(4, notches)
  }

  @Test
  fun bracketedPasteWrapsAndStripsControlBytes() {
    assertEquals(
      "$ESC[200~hello$ESC[201~",
      SwiftTUIInput.bracketedPaste("hello").decodeToString()
    )
    // An embedded ESC (which could smuggle the paste-end marker) is removed.
    assertEquals(
      "$ESC[200~ab$ESC[201~",
      SwiftTUIInput.bracketedPaste("a${ESC}b").decodeToString()
    )
    // Tabs and newlines survive.
    assertTrue(SwiftTUIInput.bracketedPaste("a\tb\nc").decodeToString().contains("a\tb\nc"))
  }

  @Test
  fun bracketedPasteIsEmptyForBlankOrControlOnlyInput() {
    assertArrayEquals(ByteArray(0), SwiftTUIInput.bracketedPaste(""))
    assertArrayEquals(ByteArray(0), SwiftTUIInput.bracketedPaste("$ESC$ESC"))
  }

  @Test
  fun fileDropIsOneFileUrlInsideABracketedPaste() {
    assertEquals(
      "$ESC[200~file:///data/user/0/app/cache/swifttui-imports/s/1/notes.txt$ESC[201~",
      SwiftTUIInput.fileDrop("/data/user/0/app/cache/swifttui-imports/s/1/notes.txt")
        .decodeToString()
    )
  }

  // The same vector is pinned on the runtime side by swift-tui's
  // DroppedPathParsingTests ("Android document import drop"): the encoder here
  // and the parser there must agree byte for byte.
  @Test
  fun fileDropEscapesEverythingThePasteTokenizerCouldReinterpret() {
    assertEquals(
      "$ESC[200~file:///data/user/0/app/cache/swifttui-imports/s/1/" +
        "it%27s%20%E5%86%99%E7%9C%9F%20100%25%20%5Ca%5C%20%22q%22.png$ESC[201~",
      SwiftTUIInput.fileDrop(
        "/data/user/0/app/cache/swifttui-imports/s/1/it's 写真 100% \\a\\ \"q\".png"
      ).decodeToString()
    )
  }

  @Test
  fun fileDropKeepsUnreservedBytesAndEscapesTheRest() {
    val unreserved = "/AZaz09-._~/"
    assertEquals(
      "$ESC[200~file://$unreserved$ESC[201~",
      SwiftTUIInput.fileDrop(unreserved).decodeToString()
    )
    val escaped = SwiftTUIInput.fileDrop("/a b\tc\nd${ESC}e😀").decodeToString()
    assertEquals("$ESC[200~file:///a%20b%09c%0Ad%1Be%F0%9F%98%80$ESC[201~", escaped)
    // Nothing between the paste markers can end the paste or split the token.
    val payload = escaped.removePrefix("$ESC[200~").removeSuffix("$ESC[201~")
    assertTrue(payload.all { it.code in 0x21..0x7E && it != '\'' && it != '\\' })
  }

  @Test
  fun fileDropRefusesPathsItCannotNameExactly() {
    assertArrayEquals(ByteArray(0), SwiftTUIInput.fileDrop(""))
    assertArrayEquals(ByteArray(0), SwiftTUIInput.fileDrop("relative/notes.txt"))
    assertArrayEquals(ByteArray(0), SwiftTUIInput.fileDrop("content://authority/document/1"))
    assertArrayEquals(ByteArray(0), SwiftTUIInput.fileDrop("/a\u0000b"))
    assertArrayEquals(ByteArray(0), SwiftTUIInput.fileDrop("/unpaired\uD83D"))
    assertArrayEquals(ByteArray(0), SwiftTUIInput.fileDrop("/unpaired\uDE00"))
  }
}
