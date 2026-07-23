package sh.swifttui.android.host

data class SwiftTUIColor(
  val hex: String
)

data class SwiftTUITerminalStyle(
  val foregroundColor: SwiftTUIColor,
  val backgroundColor: SwiftTUIColor,
  val tintColor: SwiftTUIColor
) {
  companion object {
    val Default = SwiftTUITerminalStyle(
      foregroundColor = SwiftTUIColor("#ECEFF4FF"),
      backgroundColor = SwiftTUIColor("#1E222AFF"),
      tintColor = SwiftTUIColor("#56B6C2FF")
    )
  }
}

data class SwiftTUITextLineStyle(
  val pattern: String,
  val color: SwiftTUIColor?
)

data class SwiftTUITextStyle(
  val foregroundColor: SwiftTUIColor?,
  val backgroundColor: SwiftTUIColor?,
  val emphasis: Set<String>,
  val underlineStyle: SwiftTUITextLineStyle?,
  val strikethroughStyle: SwiftTUITextLineStyle?,
  val opacity: Double
)

data class SwiftTUICell(
  val x: Int,
  val y: Int,
  val character: String,
  val spanWidth: Int,
  val continuationLeadX: Int?,
  val style: SwiftTUITextStyle?,
  val hyperlink: String?
) {
  val isContinuation: Boolean
    get() = continuationLeadX != null || spanWidth <= 0
}

data class SwiftTUIRect(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int
)

data class SwiftTUIPoint(
  val x: Int,
  val y: Int
)

data class SwiftTUIPixelSize(
  val width: Int,
  val height: Int
)

data class SwiftTUICellSize(
  val width: Int,
  val height: Int
)

/**
 * A scrollable region's extent in terminal cells: the visible [rect], the
 * current clamped scroll [offset], and the total [content] size. Mirrors the
 * Swift `AndroidHostScrollRegionSnapshot` / web `scrollRegions` wire shape.
 *
 * Touch panning of inner content is handled by the SwiftTUI core (a drag that
 * starts on scroll content is forwarded as `.dragged` and pans there), so the
 * host does not need this to pan. It is forwarded so the host can later route a
 * pan to an outer native scroll view when the inner region cannot scroll
 * further in the gesture's direction (nested-scroll chaining).
 */
data class SwiftTUIScrollRegion(
  val id: String,
  val rect: SwiftTUIRect,
  val offset: SwiftTUIPoint,
  val content: SwiftTUICellSize
) {
  /** Remaining upward scroll headroom in cells (`offset.y`). */
  val canScrollUp: Boolean get() = offset.y > 0

  /** Remaining downward scroll headroom (`offset.y < content - viewport`). */
  val canScrollDown: Boolean get() = offset.y < maxOf(0, content.height - rect.height)

  /** Remaining leftward scroll headroom in cells (`offset.x`). */
  val canScrollLeft: Boolean get() = offset.x > 0

  /** Remaining rightward scroll headroom (`offset.x < content - viewport`). */
  val canScrollRight: Boolean get() = offset.x < maxOf(0, content.width - rect.width)
}

data class SwiftTUIImageAttachment(
  val id: String,
  val bounds: SwiftTUIRect,
  val visibleBounds: SwiftTUIRect,
  val sourceKind: String,
  val sourceIdentifier: String?,
  val payloadBase64: String?,
  val payloadByteCount: Int?,
  val pixelSize: SwiftTUIPixelSize?,
  val cellPixelSize: SwiftTUIPixelSize?,
  val isResizable: Boolean,
  val scalingMode: String
)

data class SwiftTUIFocusPresentation(
  val focusedIdentity: String?,
  val semantics: String,
  val prefersTextInput: Boolean,
  val hasFocusedRegion: Boolean
) {
  companion object {
    val None = SwiftTUIFocusPresentation(
      focusedIdentity = null,
      semantics = "none",
      prefersTextInput = false,
      hasFocusedRegion = false
    )
  }
}

data class SwiftTUIAccessibilityNode(
  val id: String,
  val parentID: String?,
  val rect: SwiftTUIRect,
  val role: String,
  val label: String?,
  val hint: String?,
  val hidden: Boolean,
  val liveRegion: String?,
  val cursorAnchor: SwiftTUIPoint?,
  val isFocused: Boolean
)

data class SwiftTUIAccessibilityAnnouncement(
  val message: String,
  val politeness: String
)

data class SwiftTUIRange(
  val lowerBound: Int,
  val upperBound: Int
)

data class SwiftTUITextDamageRow(
  val row: Int,
  val columnRanges: List<SwiftTUIRange>
)

data class SwiftTUIFrame(
  val sequence: Long,
  /**
   * Consumption-order stamp for the renderer's retained-bitmap guard. On the
   * legacy keyed-JSON wire this equals [sequence] (damage is commit-relative,
   * so sequence contiguity is the partial-repaint precondition). On the
   * converged web-surface wire the decoder session assigns a contiguous
   * counter: the Swift host accumulates damage across poll-skipped frames,
   * so consumption contiguity — not sequence contiguity — is the guard.
   */
  val consumedGeneration: Long,
  val gridWidth: Int,
  val gridHeight: Int,
  val preferredGridWidth: Int?,
  val preferredGridHeight: Int?,
  val terminalStyle: SwiftTUITerminalStyle,
  val cells: List<SwiftTUICell>,
  val imageAttachments: List<SwiftTUIImageAttachment>,
  val focusedIdentity: String?,
  val focusPresentation: SwiftTUIFocusPresentation,
  val accessibilityNodes: List<SwiftTUIAccessibilityNode>,
  val accessibilityAnnouncements: List<SwiftTUIAccessibilityAnnouncement>,
  val scrollRegions: List<SwiftTUIScrollRegion>,
  val dirtyRows: List<Int>,
  val textDamageRows: List<SwiftTUITextDamageRow>,
  val requiresFullTextRepaint: Boolean,
  val requiresFullGraphicsReplay: Boolean
) {
  /**
   * The rendered cell covering a 1-based terminal [column]/[row], or `null` if
   * none. Spans are resolved to their lead cell so a tap anywhere inside a
   * wide glyph (or a hyperlink run) resolves to the owning cell. Pure, so it is
   * unit-testable without Android.
   */
  fun cellAt(column: Int, row: Int): SwiftTUICell? {
    val x = column - 1
    val y = row - 1
    if (x < 0 || y < 0) {
      return null
    }
    return cells.firstOrNull { cell ->
      !cell.isContinuation &&
        cell.y == y &&
        x >= cell.x &&
        x < cell.x + cell.spanWidth.coerceAtLeast(1)
    }
  }

  /**
   * The last (topmost) scroll region whose viewport contains a 1-based
   * terminal [column]/[row], or `null` if none. Later regions in the list are
   * nested deeper, mirroring the core's topmost-wins scroll hit-test. Pure, so
   * it is unit-testable without Android.
   */
  fun scrollRegionAt(column: Int, row: Int): SwiftTUIScrollRegion? {
    val x = column - 1
    val y = row - 1
    if (x < 0 || y < 0) {
      return null
    }
    return scrollRegions.lastOrNull { region ->
      x >= region.rect.x &&
        x < region.rect.x + region.rect.width &&
        y >= region.rect.y &&
        y < region.rect.y + region.rect.height
    }
  }

}
