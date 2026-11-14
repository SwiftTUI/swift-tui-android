package sh.swifttui.android.host

/**
 * The host editor's buffer: its text, directed selection and composing range.
 *
 * Every offset is a UTF-16 code unit of [text], the unit of Android's
 * `Editable`, Compose's `TextRange` and the runtime's published
 * `textSelection`. The host never counts user-perceived characters: the
 * runtime translates these offsets to its own grapheme offsets, because it is
 * the authority on where its characters begin and end.
 *
 * [selectionStart] is the fixed end (anchor) and [selectionEnd] the moving end
 * (head), so a backward selection has `selectionStart > selectionEnd`.
 * [compositionStart] and [compositionEnd] are `-1` when nothing is composing.
 */
internal data class SwiftTUIImeBuffer(
  val text: String = "",
  val selectionStart: Int = 0,
  val selectionEnd: Int = 0,
  val compositionStart: Int = -1,
  val compositionEnd: Int = -1
) {
  val hasComposition: Boolean
    get() = compositionStart in 0 until compositionEnd

  /**
   * The same buffer with every offset inside the text and outside a surrogate
   * pair. A selection endpoint and the end of the composing range move to the
   * end of a split pair, the start of the composing range to its beginning,
   * so the range never names half a code point. An empty or inverted
   * composing range is no composition.
   */
  fun normalized(): SwiftTUIImeBuffer {
    val start = snap(selectionStart, up = true)
    val end = snap(selectionEnd, up = true)
    // Emptiness is decided on the reported range: snapping an empty range
    // that sits inside a pair must not widen it into a composition.
    val reported = hasComposition
    val composingStart = if (reported) snap(compositionStart, up = false) else -1
    val composingEnd = if (reported) snap(compositionEnd, up = true) else -1
    val composing = reported && composingStart < composingEnd
    return SwiftTUIImeBuffer(
      text = text,
      selectionStart = start,
      selectionEnd = end,
      compositionStart = if (composing) composingStart else -1,
      compositionEnd = if (composing) composingEnd else -1
    )
  }

  /** Whether [text] is well-formed UTF-16: every surrogate belongs to a pair. */
  val isWellFormed: Boolean
    get() {
      var index = 0
      while (index < text.length) {
        val unit = text[index]
        if (Character.isHighSurrogate(unit)) {
          if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return false
          index += 2
        } else if (Character.isLowSurrogate(unit)) {
          return false
        } else {
          index++
        }
      }
      return true
    }

  /** The buffer without line breaks, each offset moved back past the removed units. */
  fun removingLineBreaks(): SwiftTUIImeBuffer {
    if (text.none(::isLineBreak)) return this
    fun moved(offset: Int): Int =
      if (offset < 0) offset else offset - text.take(offset).count(::isLineBreak)
    return SwiftTUIImeBuffer(
      text = text.filterNot(::isLineBreak),
      selectionStart = moved(selectionStart),
      selectionEnd = moved(selectionEnd),
      compositionStart = moved(compositionStart),
      compositionEnd = moved(compositionEnd)
    ).normalized()
  }

  private fun snap(offset: Int, up: Boolean): Int {
    val clamped = offset.coerceIn(0, text.length)
    val splitsPair = clamped in 1 until text.length &&
      Character.isHighSurrogate(text[clamped - 1]) && Character.isLowSurrogate(text[clamped])
    return if (!splitsPair) clamped else if (up) clamped + 1 else clamped - 1
  }
}

private fun isLineBreak(unit: Char): Boolean = unit == '\n' || unit == '\r'

/**
 * The focused text control as the runtime published it in one frame: what the
 * host editor mirrors. Offsets are UTF-16 code units of [text].
 */
internal data class SwiftTUIImeEditor(
  val target: String,
  val multiline: Boolean,
  val text: String,
  val selectionStart: Int,
  val selectionEnd: Int
) {
  /** The runtime's committed state as a buffer: nothing is composing. */
  fun buffer(): SwiftTUIImeBuffer =
    SwiftTUIImeBuffer(text, selectionStart, selectionEnd).normalized()
}

/** What one input-method edit sends to the runtime, in order. */
internal sealed interface SwiftTUIImeOutput {
  /** The whole editor state after the edit, for the `textInput` operation. */
  data class Edit(val target: String, val buffer: SwiftTUIImeBuffer) : SwiftTUIImeOutput

  /** Terminal input bytes on the ordinary input channel. */
  class Bytes(val bytes: ByteArray) : SwiftTUIImeOutput
}

/**
 * Keeps the invisible host editor a mirror of the runtime's focused text
 * control and turns input-method edits into full-state edit records.
 *
 * Source of truth: the runtime owns committed text and selection. The buffer
 * is seeded from the frame when a text control gains focus and re-adopted
 * whenever the frame's text or selection differs from it. The input method
 * owns the composing range and authors edits; each edit replaces the control's
 * whole editing state. A frame older than the last edit is recognised by its
 * acknowledgement watermark and is not mirrored, so an echo cannot undo typing
 * that the runtime has not processed yet.
 *
 * Pure and Android-free. `SwiftTUIHostState` feeds it frames and edits and
 * performs the sends; `SwiftTUIHostView` shows [buffer] to the input method.
 */
internal class SwiftTUIImeSession(
  private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
  private data class Pending(val id: Long, val sentAt: Long)

  var buffer: SwiftTUIImeBuffer = SwiftTUIImeBuffer()
    private set

  private var editor: SwiftTUIImeEditor? = null
  private var pending: Pending? = null

  /** Whether the buffer mirrors a runtime text control. */
  val isMirroring: Boolean
    get() = editor != null

  fun reset() {
    editor = null
    pending = null
    buffer = SwiftTUIImeBuffer()
  }

  /**
   * Reconciles one published frame. [focused] is the control to mirror, or
   * `null` when focus is not on a mirrorable text control. [acknowledged] is
   * the frame's action-response request ID: the last request the runtime had
   * processed when it rendered this frame.
   */
  fun observe(focused: SwiftTUIImeEditor?, acknowledged: Long?) {
    val previous = editor
    if (focused == null) {
      // Focus left text input, or the control cannot be mirrored. Whatever was
      // composing is already in the runtime's text; the runtime commits it.
      if (previous != null) reset()
      return
    }
    editor = focused
    if (previous == null || previous.target != focused.target) {
      // Another control, or a recreated one: start from the runtime's state.
      pending = null
      buffer = focused.buffer()
      return
    }
    pending?.let { edit ->
      val settled = (acknowledged != null && acknowledged >= edit.id) ||
        now() - edit.sentAt >= ACKNOWLEDGEMENT_TIMEOUT_MILLIS
      if (!settled) return
      pending = null
    }
    val runtime = focused.buffer()
    if (buffer.text != runtime.text || buffer.selectionStart != runtime.selectionStart ||
      buffer.selectionEnd != runtime.selectionEnd
    ) {
      // The runtime changed the state itself: a key or paste it handled, an
      // application rewrite, a refused edit. Its state wins, and the change
      // ends any composition.
      buffer = runtime
    }
  }

  /**
   * Applies one edit from the input method. Returns what to send, in order;
   * empty when the buffer does not mirror a control or nothing changed.
   */
  fun edit(candidate: SwiftTUIImeBuffer): List<SwiftTUIImeOutput> {
    val editor = editor ?: return emptyList()
    var next = candidate.normalized()
    if (!next.isWellFormed) {
      // Half a surrogate pair cannot be encoded. Keep the editor's state and
      // wait for the update that completes the pair; it carries everything.
      buffer = next
      return emptyList()
    }
    var submits = false
    if (!editor.multiline && next.text.any(::isLineBreak)) {
      // A single-line control has no line breaks. One typed immediately before
      // the caret is the Return key; any other (a pasted block) is dropped.
      val caret = next.selectionEnd
      submits = next.selectionStart == caret && caret > 0 && isLineBreak(next.text[caret - 1])
      next = next.removingLineBreaks()
    }
    val outputs = ArrayList<SwiftTUIImeOutput>(2)
    if (next != buffer) {
      buffer = next
      outputs.add(SwiftTUIImeOutput.Edit(editor.target, next))
    }
    if (submits) outputs.add(SwiftTUIImeOutput.Bytes(RETURN))
    return outputs
  }

  /**
   * Records the outcome of sending an [SwiftTUIImeOutput.Edit]: its request ID,
   * or `null` when the runtime refused the record. A refused edit leaves the
   * mirror ahead of the runtime, so the next frame is adopted.
   */
  fun editSent(requestID: Long?) {
    pending = requestID?.let { Pending(it, now()) }
  }

  companion object {
    /** A lost acknowledgement must not stop the mirror following the runtime. */
    const val ACKNOWLEDGEMENT_TIMEOUT_MILLIS = 1_000L

    private val RETURN = byteArrayOf('\r'.code.toByte())

    private val EDITOR_ROLES = setOf("textField", "textEditor")

    /**
     * The control [frame] says the host editor should mirror, or `null`.
     *
     * Mirroring needs everything the contract reads: keyboard focus on exactly
     * one enabled, visible, nonsecure text control that publishes an action
     * target, the `editText` action, its text and its selection. A secure
     * field never publishes its text, so it is not mirrored.
     */
    fun focusedEditor(frame: SwiftTUIFrame): SwiftTUIImeEditor? {
      if (!frame.focusPresentation.prefersTextInput) return null
      val node = frame.accessibilityNodes.singleOrNull { it.isFocused && it.role in EDITOR_ROLES }
        ?: return null
      val target = node.actionTarget ?: return null
      val text = (node.value as? SwiftTUIAccessibilityValue.TextValue)?.value ?: return null
      val selection = node.textSelection ?: return null
      if (node.hidden || !node.isEnabled || "editText" !in node.actions || target.isEmpty()) return null
      if (selection.anchor !in 0..text.length || selection.head !in 0..text.length) return null
      return SwiftTUIImeEditor(
        target = target,
        multiline = node.role == "textEditor",
        text = text,
        selectionStart = selection.anchor,
        selectionEnd = selection.head
      )
    }

    /**
     * The `textInput` record for [buffer]: RS, the request ID, the
     * percent-encoded target and text, the directed selection and the
     * composing range (`-1:-1` when nothing is composing), LF. `null` when
     * the record exceeds the wire budget or the text is not well-formed.
     */
    fun encode(requestID: Long, target: String, buffer: SwiftTUIImeBuffer): ByteArray? {
      if (requestID <= 0 || target.isEmpty() || !buffer.isWellFormed) return null
      val composition =
        if (buffer.hasComposition) "${buffer.compositionStart}:${buffer.compositionEnd}" else "-1:-1"
      val bytes = (
        "\u001EtextInput:$requestID:${percentEncode(target)}:${percentEncode(buffer.text)}:" +
          "${buffer.selectionStart}:${buffer.selectionEnd}:$composition\n"
        ).encodeToByteArray()
      return bytes.takeIf { it.size <= SwiftTUIWireBudget.RECORD_BYTES }
    }
  }
}
