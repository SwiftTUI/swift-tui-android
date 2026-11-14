package sh.swifttui.android.host

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host half of the input-method contract: the editor mirrors the runtime's
 * focused text control, and each input-method edit becomes one full-state
 * record with UTF-16 offsets. Offsets here are Android's code units; the
 * comments name the user-perceived characters they must not be mistaken for.
 */
class SwiftTUIImeSessionTest {
  private var now = 0L
  private val session = SwiftTUIImeSession { now }

  private fun editor(
    text: String,
    caret: Int = text.length,
    target: String = "field#1",
    multiline: Boolean = false
  ) = SwiftTUIImeEditor(target, multiline, text, caret, caret)

  private fun buffer(text: String, caret: Int, composing: IntRange? = null) =
    SwiftTUIImeBuffer(
      text, caret, caret,
      composing?.first ?: -1,
      composing?.let { it.last + 1 } ?: -1
    )

  /** Applies an edit and returns the single full-state record's buffer. */
  private fun edit(next: SwiftTUIImeBuffer): SwiftTUIImeBuffer {
    val output = session.edit(next).single() as SwiftTUIImeOutput.Edit
    assertEquals("field#1", output.target)
    return output.buffer
  }

  // --- Seeding and source of truth -----------------------------------------

  @Test
  fun aFocusedControlSeedsTheEditorWithTheRuntimeTextAndCaret() {
    assertFalse(session.isMirroring)
    session.observe(editor("前后", caret = 1), acknowledged = null)
    assertTrue(session.isMirroring)
    assertEquals(SwiftTUIImeBuffer("前后", 1, 1), session.buffer)
  }

  @Test
  fun losingTextFocusLeavesTheMirrorAndEmptiesTheEditor() {
    session.observe(editor("abc"), acknowledged = null)
    edit(buffer("abcni", 5, 3..4))
    session.observe(null, acknowledged = null)
    assertFalse(session.isMirroring)
    assertEquals(SwiftTUIImeBuffer(), session.buffer)
    // No edit can be authored without a mirrored control.
    assertTrue(session.edit(buffer("x", 1)).isEmpty())
  }

  @Test
  fun aControlSwitchStartsFromTheNewControlAndDropsTheComposition() {
    session.observe(editor("abc"), acknowledged = null)
    edit(buffer("abcni", 5, 3..4))
    session.editSent(1)
    session.observe(editor("other", caret = 2, target = "field#2"), acknowledged = null)
    assertEquals(SwiftTUIImeBuffer("other", 2, 2), session.buffer)
    // The new control's edits name the new target.
    val output = session.edit(buffer("otXher", 3)).single() as SwiftTUIImeOutput.Edit
    assertEquals("field#2", output.target)
  }

  @Test
  fun aRuntimeChangeIsAdoptedAndEndsTheComposition() {
    session.observe(editor("hello"), acknowledged = null)
    edit(buffer("hello", 5, 0..4))
    session.editSent(1)
    // The frame that processed the edit agrees: the composition stays.
    session.observe(editor("hello"), acknowledged = 1)
    assertEquals(buffer("hello", 5, 0..4), session.buffer)
    // The application cleared the field (for example on submit).
    session.observe(editor(""), acknowledged = 1)
    assertEquals(SwiftTUIImeBuffer(), session.buffer)
    // A runtime caret move (an arrow key, a tap) is adopted too.
    session.observe(editor("hello", caret = 2), acknowledged = 1)
    assertEquals(SwiftTUIImeBuffer("hello", 2, 2), session.buffer)
  }

  @Test
  fun aFrameOlderThanTheLastEditIsNotMirrored() {
    session.observe(editor(""), acknowledged = null)
    edit(buffer("n", 1, 0..0))
    session.editSent(1)
    edit(buffer("ni", 2, 0..1))
    session.editSent(2)
    // Rendered after edit 1 only: mirroring it would undo the second key.
    session.observe(editor("n"), acknowledged = 1)
    assertEquals(buffer("ni", 2, 0..1), session.buffer)
    // Rendered before either edit, with no watermark at all.
    session.observe(editor(""), acknowledged = null)
    assertEquals(buffer("ni", 2, 0..1), session.buffer)
    // Rendered after edit 2: agrees, and the composition survives.
    session.observe(editor("ni"), acknowledged = 2)
    assertEquals(buffer("ni", 2, 0..1), session.buffer)
    // A later watermark from another request on the same channel also settles.
    edit(buffer("nih", 3, 0..2))
    session.editSent(3)
    session.observe(editor("NIH"), acknowledged = 9)
    assertEquals(SwiftTUIImeBuffer("NIH", 3, 3), session.buffer)
  }

  @Test
  fun aRefusedOrUnacknowledgedEditCannotWedgeTheMirror() {
    session.observe(editor("abc"), acknowledged = null)
    edit(buffer("abcd", 4))
    session.editSent(null) // the record was refused at ingress
    session.observe(editor("abc"), acknowledged = null)
    assertEquals(SwiftTUIImeBuffer("abc", 3, 3), session.buffer)

    edit(buffer("abcd", 4))
    session.editSent(5)
    now = SwiftTUIImeSession.ACKNOWLEDGEMENT_TIMEOUT_MILLIS - 1
    session.observe(editor("abc"), acknowledged = null)
    assertEquals(SwiftTUIImeBuffer("abcd", 4, 4), session.buffer)
    now = SwiftTUIImeSession.ACKNOWLEDGEMENT_TIMEOUT_MILLIS
    session.observe(editor("abc"), acknowledged = null)
    assertEquals(SwiftTUIImeBuffer("abc", 3, 3), session.buffer)
  }

  // --- Composition ----------------------------------------------------------

  @Test
  fun cjkCandidateCompositionUpdatesReplacesAndCommitsOnceMidString() {
    session.observe(editor("前后", caret = 1), acknowledged = null)
    assertEquals(buffer("前n后", 2, 1..1), edit(buffer("前n后", 2, 1..1)))
    assertEquals(buffer("前ni后", 3, 1..2), edit(buffer("前ni后", 3, 1..2)))
    assertEquals(buffer("前nihao后", 6, 1..5), edit(buffer("前nihao后", 6, 1..5)))
    // The candidate replaces the composing range; the suffix is the host's own.
    assertEquals(buffer("前你好后", 3), edit(buffer("前你好后", 3)))
    // The same state again sends nothing: a commit is one record.
    assertTrue(session.edit(buffer("前你好后", 3)).isEmpty())
  }

  @Test
  fun finishingAndCancellingAreDistinctFullStates() {
    session.observe(editor("ab", caret = 1), acknowledged = null)
    edit(buffer("anib", 3, 1..2))
    // finishComposingText: same text, no composing range.
    assertEquals(buffer("anib", 3), edit(buffer("anib", 3)))
    edit(buffer("anihaob", 6, 3..5))
    // The pre-edit text is emptied: the text and caret before the composition.
    assertEquals(buffer("anib", 3), edit(buffer("anib", 3)))
  }

  @Test
  fun markingExistingTextAndMovingTheSelectionSendNoTextChange() {
    session.observe(editor("hello world"), acknowledged = null)
    // setComposingRegion over "hello".
    assertEquals(buffer("hello world", 11, 0..4), edit(buffer("hello world", 11, 0..4)))
    // setSelection, backward: anchor 5, head 0.
    val selected = edit(SwiftTUIImeBuffer("hello world", 5, 0))
    assertEquals(5, selected.selectionStart)
    assertEquals(0, selected.selectionEnd)
    assertFalse(selected.hasComposition)
  }

  // --- UTF-16 offsets are code units, not characters -------------------------

  @Test
  fun supplementaryPlaneEmojiKeepsItsUtf16OffsetsAndNeverSplitsAPair() {
    // "a😀b": a=1 unit, 😀=2 units, b=1 unit; three characters, four units.
    session.observe(editor("ab", caret = 1), acknowledged = null)
    val composed = edit(buffer("a😀b", 3, 1..2))
    assertEquals(3, composed.selectionEnd)
    assertEquals(1, composed.compositionStart)
    assertEquals(3, composed.compositionEnd)

    // An input method reporting offsets inside the pair: the caret and the
    // end of the range move to its end, the start of the range to its start.
    val split = SwiftTUIImeBuffer("a😀b", 2, 2, 2, 2).normalized()
    assertEquals(SwiftTUIImeBuffer("a😀b", 3, 3), split)
    val inside = SwiftTUIImeBuffer("a😀b", 2, 2, 2, 4).normalized()
    assertEquals(SwiftTUIImeBuffer("a😀b", 3, 3, 1, 4), inside)
  }

  @Test
  fun combiningMarksAndJoinedEmojiAreSentAsCodeUnitsForTheRuntimeToTranslate() {
    session.observe(editor(""), acknowledged = null)
    // A dead-key accent composed after its base: units e=0, U+0301=1.
    val accent = edit(buffer("é", 2, 1..1))
    assertEquals(1, accent.compositionStart)
    assertEquals(2, accent.compositionEnd)
    // 👩🏽‍💻 is seven units and one character; offsets stay code units.
    val joined = edit(buffer("é👩🏽‍💻", 9))
    assertEquals(9, joined.selectionEnd)
    assertEquals("é👩🏽‍💻".length, 9)
  }

  @Test
  fun halfASurrogatePairIsHeldBackUntilThePairIsComplete() {
    session.observe(editor(""), acknowledged = null)
    val high = "😀"[0].toString()
    assertTrue(session.edit(buffer(high, 1)).isEmpty())
    assertEquals(high, session.buffer.text)
    assertNull(SwiftTUIImeSession.encode(1, "t", SwiftTUIImeBuffer(high, 1, 1)))
    assertEquals(buffer("😀", 2), edit(buffer("😀", 2)))
  }

  @Test
  fun offsetsOutsideTheTextAreClampedAndAnEmptyRangeIsNoComposition() {
    val clamped = SwiftTUIImeBuffer("ab", -3, 9, 1, 9).normalized()
    assertEquals(SwiftTUIImeBuffer("ab", 0, 2, 1, 2), clamped)
    assertFalse(SwiftTUIImeBuffer("ab", 2, 2, 1, 1).normalized().hasComposition)
    assertFalse(SwiftTUIImeBuffer("ab", 2, 2, 2, 1).normalized().hasComposition)
  }

  // --- Line breaks ----------------------------------------------------------

  @Test
  fun returnInASingleLineControlCommitsTheCompositionThenSubmits() {
    session.observe(editor(""), acknowledged = null)
    edit(buffer("hel", 3, 0..2))
    // The input method finishes its composition and types Return in one batch.
    val outputs = session.edit(buffer("hel\n", 4))
    assertEquals(2, outputs.size)
    assertEquals(buffer("hel", 3), (outputs[0] as SwiftTUIImeOutput.Edit).buffer)
    assertArrayEquals(byteArrayOf(0x0D), (outputs[1] as SwiftTUIImeOutput.Bytes).bytes)
    assertEquals(buffer("hel", 3), session.buffer)
    // Return on committed text is the key alone.
    val again = session.edit(buffer("hel\n", 4)).single() as SwiftTUIImeOutput.Bytes
    assertArrayEquals(byteArrayOf(0x0D), again.bytes)
  }

  @Test
  fun aPastedBlockLosesItsLineBreaksInASingleLineControlWithoutSubmitting() {
    session.observe(editor(""), acknowledged = null)
    assertEquals(buffer("onetwo", 6), edit(buffer("one\r\ntwo", 8)))
    assertEquals(buffer("onetwo", 6), session.buffer)
  }

  @Test
  fun anEditorKeepsLineBreaksAsText() {
    session.observe(editor("", multiline = true), acknowledged = null)
    assertEquals(buffer("a\nb", 3), edit(buffer("a\nb", 3)))
  }

  // --- Record ---------------------------------------------------------------

  @Test
  fun theRecordCarriesTextSelectionAndCompositionLosslessly() {
    val record = SwiftTUIImeSession.encode(42, "field:1#3", buffer("前ni后", 3, 1..2))!!
    assertEquals(
      "\u001EtextInput:42:field%3A1%233:%E5%89%8Dni%E5%90%8E:3:3:1:3\n",
      record.decodeToString()
    )
    val committed = SwiftTUIImeSession.encode(7, "t", SwiftTUIImeBuffer("a b:c\n\u001E%", 6, 0))!!
    assertEquals("\u001EtextInput:7:t:a%20b%3Ac%0A%1E%25:6:0:-1:-1\n", committed.decodeToString())
    // Framing bytes appear only as the record's own introducer and terminator.
    assertEquals(1, committed.count { it == 0x1E.toByte() })
    assertEquals(1, committed.count { it == 0x0A.toByte() })
  }

  @Test
  fun anOverBudgetOrMalformedRecordIsNotEncoded() {
    assertNull(SwiftTUIImeSession.encode(0, "t", SwiftTUIImeBuffer("a", 1, 1)))
    assertNull(SwiftTUIImeSession.encode(1, "", SwiftTUIImeBuffer("a", 1, 1)))
    val large = "a".repeat(SwiftTUIWireBudget.RECORD_BYTES)
    assertNull(SwiftTUIImeSession.encode(1, "t", SwiftTUIImeBuffer(large, 0, 0)))
    assertNotNull(SwiftTUIImeSession.encode(1, "t", SwiftTUIImeBuffer("a".repeat(1024), 0, 0)))
  }

  // --- What the frame must publish ------------------------------------------

  private fun frame(node: String, prefersTextInput: Boolean = true): SwiftTUIFrame =
    SwiftTUIWebSurfaceSession().decode(
      "\u001Esurface:" +
        """{"version":2,"width":1,"height":1,"styles":[null],"rows":[[[0,"x",1,0]]],""" +
        """"focusPresentation":{"focusedIdentity":"f","semantics":"edit",""" +
        """"prefersTextInput":$prefersTextInput,"hasFocusedRegion":true},""" +
        """"accessibilityTree":[$node]}"""
    )!!

  private fun node(
    role: String = "textField",
    extra: String = """"value":{"type":"text","value":"a😀b"},"textSelection":[3,1]"""
  ) =
    """{"id":"f","rect":[0,0,1,1],"role":"$role","isFocused":true,"actionTarget":"f#1",""" +
      """"actions":["focus","setValue","editText","selectText"],$extra}"""

  @Test
  fun theFocusedTextControlIsMirroredWithItsUtf16Selection() {
    val decoded = frame(node())
    assertEquals(SwiftTUITextSelection(anchor = 3, head = 1), decoded.accessibilityNodes.single().textSelection)
    assertEquals(
      SwiftTUIImeEditor("f#1", multiline = false, text = "a😀b", selectionStart = 3, selectionEnd = 1),
      SwiftTUIImeSession.focusedEditor(decoded)
    )
    assertTrue(SwiftTUIImeSession.focusedEditor(frame(node(role = "textEditor")))!!.multiline)
  }

  @Test
  fun controlsTheFrameDoesNotFullyDescribeStayOnTheBytePath() {
    // A secure field never publishes its text, whatever the record says.
    assertNull(SwiftTUIImeSession.focusedEditor(frame(node(role = "secureField"))))
    assertNull(frame(node(role = "secureField")).accessibilityNodes.single().textSelection)
    // Focus that does not want text input.
    assertNull(SwiftTUIImeSession.focusedEditor(frame(node(), prefersTextInput = false)))
    // No selection, no value, no edit action, disabled, hidden, bad offsets.
    val value = """"value":{"type":"text","value":"ab"}"""
    for (extra in listOf(
      value,
      """"textSelection":[0,0]""",
      """$value,"textSelection":[0,3]""",
      """$value,"textSelection":[0]""",
      """$value,"textSelection":[0,1,2]""",
      """$value,"textSelection":[-1,0]""",
      """$value,"textSelection":[0,0],"isEnabled":false""",
      """$value,"textSelection":[0,0],"hidden":true"""
    )) {
      assertNull(extra, SwiftTUIImeSession.focusedEditor(frame(node(extra = extra))))
    }
    val noEdit = node().replace(""","editText"""", "")
    assertNull(SwiftTUIImeSession.focusedEditor(frame(noEdit)))
    // An omitted accessibility tree (over budget) has nothing to mirror.
    val bare = SwiftTUIWebSurfaceSession().decode(
      "\u001Esurface:" +
        """{"version":2,"width":1,"height":1,"styles":[null],"rows":[[[0,"x",1,0]]],""" +
        """"focusPresentation":{"semantics":"edit","prefersTextInput":true,"hasFocusedRegion":true}}"""
    )!!
    assertNull(SwiftTUIImeSession.focusedEditor(bare))
  }

  @Test
  fun inputMethodRequestIdsShareTheCounterWithoutAnOutstandingEntry() {
    val requests = SwiftTUIAccessibilityRequests { now }
    val action = requests.issue("a")!!
    val edit = requests.allocate()!!
    assertEquals(action + 1, edit)
    // The edit's acknowledgement is the session's, never the accessibility UI's.
    assertNull(requests.accept(SwiftTUIAccessibilityActionResponse(edit.toString(), "a", "accepted")))
    assertNotNull(requests.accept(SwiftTUIAccessibilityActionResponse(action.toString(), "a", "accepted")))
    assertEquals(edit + 1, requests.issue("a"))
  }
}
