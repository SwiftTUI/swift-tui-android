package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decoder session for the shared SwiftTUI `web-surface` wire (the converged
 * cross-host format — convergence proposal 2026-07-22-002): RS-framed
 * `surface:{json}` records, versions 1/2 (full frames) and 3 (delta
 * frames against the previously decoded baseline).
 *
 * The normative contract lives upstream in
 * [HOST-WIRE-CONTRACT.md](https://github.com/SwiftTUI/swift-tui/blob/main/docs/HOST-WIRE-CONTRACT.md);
 * this decoder defers its wire-evolution policy to that document.
 *
 * Records are mapped into [SwiftTUIFrame], so the renderer, accessibility
 * overlay, damage plan, and hit-testing consume converged frames unchanged.
 * The session holds the delta baseline; [reset] clears it (a fresh scene
 * start re-declares capabilities, so the Swift host re-keyframes and the
 * baselines stay in lockstep).
 *
 * Reference semantics: the browser decoder
 * (`WebHostSurfaceTransport.ts` in swift-tui-web) — deltas replace whole
 * rows over a same-size baseline and are dropped when no usable baseline
 * exists.
 */
class SwiftTUIWebSurfaceSession {
  private data class CellTuple(
    val x: Int,
    val text: String,
    val span: Int,
    val styleIndex: Int
  )

  private var baselineRows: List<List<CellTuple>>? = null
  /**
   * The retained style table. Held across records because a delta may carry
   * only the styles it added, keyed by `stylesBase` — the negotiated
   * `styleAppend` shape.
   */
  private var baselineStyles: List<SwiftTUITextStyle?> = emptyList()
  private var baselineWidth = 0
  private var baselineHeight = 0
  private var lastEpoch: Long? = null
  private var lastGeneration: Long? = null
  private var consumedGeneration = 0L

  internal var pendingResyncScope: String? = null
    private set

  fun reset() {
    baselineRows = null
    baselineStyles = emptyList()
    baselineWidth = 0
    baselineHeight = 0
    lastEpoch = null
    lastGeneration = null
    pendingResyncScope = null
    consumedGeneration = 0L
  }

  internal fun requestKeyframeRecovery() {
    pendingResyncScope = KEYFRAME_RESYNC_SCOPE
  }

  /**
   * Decodes one web-surface record into a frame, or `null` when the record
   * cannot be applied (a delta without a usable baseline — the Swift host
   * re-keyframes on size changes and declarations, so this is a transient
   * guard, not an error path). A record declaring a newer version than
   * [SUPPORTED_WEB_SURFACE_VERSION] fails loudly (the F57 skew guard).
   */
  fun decode(payload: String): SwiftTUIFrame? {
    require(payload.startsWith(RECORD_PREFIX)) { "not a web-surface record" }
    val record = JSONObject(payload.removePrefix(RECORD_PREFIX).trimEnd('\n'))
    val version = record.optInt("version", 0)
    require(version <= SUPPORTED_WEB_SURFACE_VERSION) {
      "web-surface version $version is newer than the supported " +
        "$SUPPORTED_WEB_SURFACE_VERSION; update the swift-tui-android host library."
    }
    return when {
      version == 1 || version == 2 -> decodeFull(record)
      version == 3 && record.optionalStringWeb("encoding") == "delta" -> decodeDelta(record)
      else -> null
    }
  }

  private fun decodeFull(record: JSONObject): SwiftTUIFrame {
    val stamp = record.fullFrameStamp()
    val rows = parseRowTuples(record.optJSONArray("rows"))
    val styles = parseStyleTable(record.optJSONArray("styles"))
    baselineRows = rows
    baselineStyles = styles
    baselineWidth = record.optInt("width")
    baselineHeight = record.optInt("height")
    lastEpoch = stamp.epoch
    lastGeneration = stamp.generation
    pendingResyncScope = null
    return buildFrame(record, rows, styles)
  }

  private fun decodeDelta(record: JSONObject): SwiftTUIFrame? {
    // Validate the complete optional stamp tuple before consulting or mutating
    // session state. A malformed record is structural failure even while a
    // prior keyframe request is outstanding.
    val stamp = record.deltaFrameStamp()
    if (pendingResyncScope != null) {
      return null
    }
    val carriesBaselineStamp = stamp.epoch != null
    val baseline = baselineRows ?: return refuseDelta(carriesBaselineStamp)
    val width = record.optInt("width")
    val height = record.optInt("height")
    if (width != baselineWidth || height != baselineHeight) {
      return refuseDelta(carriesBaselineStamp)
    }

    if (
      carriesBaselineStamp &&
      (stamp.epoch != lastEpoch || stamp.baselineGeneration != lastGeneration)
    ) {
      return refuseDelta(shouldRequestKeyframe = true)
    }

    // The negotiated append shape: `stylesBase` names where this record's
    // styles splice onto the retained table. A base that does not match is a
    // structural break, not a recoverable one — splicing at the wrong offset
    // would silently repaint cells in the wrong style.
    val stylesBase = record.optionalIntWeb("stylesBase")
    val styles = if (stylesBase == null) {
      parseStyleTable(record.optJSONArray("styles"))
    } else {
      if (stylesBase != baselineStyles.size) {
        return refuseDelta(shouldRequestKeyframe = true)
      }
      baselineStyles + parseStyleTable(record.optJSONArray("styles"))
    }

    val rows = baseline.toMutableList()
    val deltaRows = record.optJSONArray("deltaRows") ?: JSONArray()
    for (index in 0 until deltaRows.length()) {
      val entry = deltaRows.optJSONArray(index) ?: return refuseDelta(carriesBaselineStamp)
      val row = entry.optInt(0, -1)
      if (row < 0 || row >= height || row >= rows.size) {
        return refuseDelta(carriesBaselineStamp)
      }
      rows[row] = parseCellTuples(row, entry.optJSONArray(1))
    }
    baselineRows = rows
    baselineStyles = styles
    lastEpoch = stamp.epoch
    lastGeneration = stamp.generation
    return buildFrame(record, rows, styles)
  }

  private fun refuseDelta(shouldRequestKeyframe: Boolean): SwiftTUIFrame? {
    if (shouldRequestKeyframe) {
      requestKeyframeRecovery()
    }
    return null
  }

  private fun parseRowTuples(array: JSONArray?): List<List<CellTuple>> = buildList {
    val rowsArray = array ?: return@buildList
    for (y in 0 until rowsArray.length()) {
      add(parseCellTuples(y, rowsArray.optJSONArray(y)))
    }
  }

  private fun parseCellTuples(y: Int, array: JSONArray?): List<CellTuple> = buildList {
    val cellsArray = array ?: return@buildList
    for (index in 0 until cellsArray.length()) {
      val tuple = cellsArray.optJSONArray(index) ?: continue
      add(
        CellTuple(
          x = tuple.optInt(0),
          text = tuple.optString(1, " "),
          span = tuple.optInt(2, 1),
          styleIndex = tuple.optInt(3, 0)
        )
      )
    }
  }

  private fun buildFrame(
    record: JSONObject,
    rowTuples: List<List<CellTuple>>,
    styles: List<SwiftTUITextStyle?>
  ): SwiftTUIFrame {
    val linkTargets = record.optJSONArray("linkTargets").strings()
    val linkRuns = record.optJSONArray("links")

    val cells = buildList {
      rowTuples.forEachIndexed { y, tuples ->
        for (tuple in tuples) {
          add(
            SwiftTUICell(
              x = tuple.x,
              y = y,
              character = tuple.text,
              spanWidth = tuple.span,
              continuationLeadX = null,
              style = styles.getOrNull(tuple.styleIndex),
              hyperlink = null
            )
          )
        }
      }
    }
    val linkedCells = applyLinkRuns(cells, linkRuns, linkTargets)

    val damage = record.optJSONObject("damage")
    val textDamageRows = parseDamageTextRows(damage?.optJSONArray("textRows"))
    val focusPresentation = record.optJSONObject("focusPresentation")
      ?.toWebFocusPresentation() ?: SwiftTUIFocusPresentation.None

    consumedGeneration += 1
    return SwiftTUIFrame(
      sequence = record.optLong("sequence", 0L),
      // Contiguous per decoded frame: the converged wire's damage is
      // consumption-relative (the Swift host accumulates it across skipped
      // polls), so partial repaints stay legal across sequence gaps.
      consumedGeneration = consumedGeneration,
      gridWidth = record.optInt("width"),
      gridHeight = record.optInt("height"),
      preferredGridWidth = record.optionalIntWeb("preferredGridWidth"),
      preferredGridHeight = record.optionalIntWeb("preferredGridHeight"),
      terminalStyle = record.optJSONObject("terminalStyle")?.toWebTerminalStyle()
        ?: SwiftTUITerminalStyle.Default,
      cells = linkedCells,
      imageAttachments = record.optJSONArray("images").objects().map { it.toWebImageAttachment() },
      focusedIdentity = focusPresentation.focusedIdentity,
      focusPresentation = focusPresentation,
      accessibilityNodes = record.optJSONArray("accessibilityTree").objects().map {
        it.toWebAccessibilityNode()
      },
      accessibilityAnnouncements = record.optJSONArray("accessibilityAnnouncements")
        .objects()
        .map {
          SwiftTUIAccessibilityAnnouncement(
            message = it.optString("message"),
            politeness = it.optString("politeness", "polite")
          )
        },
      scrollRegions = record.optJSONArray("scrollRegions").objects().map { it.toWebScrollRegion() },
      dirtyRows = textDamageRows.map { it.row }.distinct().sorted(),
      textDamageRows = textDamageRows,
      requiresFullTextRepaint = damage?.optBoolean("requiresFullTextRepaint", true) ?: true,
      requiresFullGraphicsReplay = damage?.optBoolean("requiresFullGraphicsReplay", true) ?: true
    )
  }

  private fun parseStyleTable(array: JSONArray?): List<SwiftTUITextStyle?> = buildList {
    val styles = array ?: return@buildList
    for (index in 0 until styles.length()) {
      add(styles.optJSONObject(index)?.toWebTextStyle())
    }
  }

  private fun applyLinkRuns(
    cells: List<SwiftTUICell>,
    linkRuns: JSONArray?,
    linkTargets: List<String>
  ): List<SwiftTUICell> {
    val runs = linkRuns ?: return cells
    if (runs.length() == 0 || linkTargets.isEmpty()) {
      return cells
    }
    // [rowIndex, [[start, span, targetIndex], …]] — every lead cell whose x
    // falls inside a run carries that run's target.
    val targetsByRowAndX = HashMap<Long, String>()
    for (index in 0 until runs.length()) {
      val entry = runs.optJSONArray(index) ?: continue
      val y = entry.optInt(0, -1)
      val rowRuns = entry.optJSONArray(1) ?: continue
      for (runIndex in 0 until rowRuns.length()) {
        val run = rowRuns.optJSONArray(runIndex) ?: continue
        val start = run.optInt(0)
        val span = run.optInt(1)
        val target = linkTargets.getOrNull(run.optInt(2, -1)) ?: continue
        for (x in start until start + span) {
          targetsByRowAndX[cellKey(x, y)] = target
        }
      }
    }
    if (targetsByRowAndX.isEmpty()) {
      return cells
    }
    return cells.map { cell ->
      targetsByRowAndX[cellKey(cell.x, cell.y)]?.let { cell.copy(hyperlink = it) } ?: cell
    }
  }

  private fun cellKey(x: Int, y: Int): Long = y.toLong() shl 32 or (x.toLong() and 0xFFFFFFFFL)

  private fun parseDamageTextRows(array: JSONArray?): List<SwiftTUITextDamageRow> = buildList {
    val textRows = array ?: return@buildList
    // [row, [[lowerBound, upperBound], …]] tuples.
    for (index in 0 until textRows.length()) {
      val entry = textRows.optJSONArray(index) ?: continue
      val ranges = buildList {
        val rangesArray = entry.optJSONArray(1) ?: return@buildList
        for (rangeIndex in 0 until rangesArray.length()) {
          val range = rangesArray.optJSONArray(rangeIndex) ?: continue
          add(SwiftTUIRange(lowerBound = range.optInt(0), upperBound = range.optInt(1)))
        }
      }
      add(SwiftTUITextDamageRow(row = entry.optInt(0), columnRanges = ranges))
    }
  }

  companion object {
    const val RECORD_PREFIX = "\u001Esurface:"
    internal const val KEYFRAME_RESYNC_SCOPE = "keyframe"

    /**
     * The newest web-surface version this decoder understands. The Kotlin
     * capability declaration derives from it, so landing support for a newer
     * version automatically declares it (mirrors
     * [SwiftTUIFrame.SUPPORTED_SCHEMA_VERSION]).
     */
    const val SUPPORTED_WEB_SURFACE_VERSION = 3

    fun isWebSurfaceRecord(payload: String): Boolean = payload.startsWith(RECORD_PREFIX)
  }
}

private data class FullFrameStamp(
  val epoch: Long?,
  val generation: Long?
)

private data class DeltaFrameStamp(
  val epoch: Long?,
  val generation: Long?,
  val baselineGeneration: Long?
)

private fun JSONObject.fullFrameStamp(): FullFrameStamp {
  requireTuplePresence("full frame", "epoch", "gen")
  return FullFrameStamp(
    epoch = optionalSafeWireInteger("epoch"),
    generation = optionalSafeWireInteger("gen")
  )
}

private fun JSONObject.deltaFrameStamp(): DeltaFrameStamp {
  requireTuplePresence("delta frame", "epoch", "gen", "baselineGen")
  return DeltaFrameStamp(
    epoch = optionalSafeWireInteger("epoch"),
    generation = optionalSafeWireInteger("gen"),
    baselineGeneration = optionalSafeWireInteger("baselineGen")
  )
}

private fun JSONObject.requireTuplePresence(recordKind: String, vararg names: String) {
  val presentCount = names.count(::has)
  require(presentCount == 0 || presentCount == names.size) {
    "web-surface $recordKind stamp must contain either none or all of " +
      names.joinToString()
  }
}

/** Web text emphasis rides the wire as the Swift `TextEmphasis` bitmask. */
private fun emphasisTokens(bitmask: Int): Set<String> = buildSet {
  if (bitmask and (1 shl 0) != 0) add("bold")
  if (bitmask and (1 shl 1) != 0) add("italic")
  if (bitmask and (1 shl 2) != 0) add("faint")
  if (bitmask and (1 shl 3) != 0) add("blink")
  if (bitmask and (1 shl 4) != 0) add("reverse")
}

private fun JSONObject.toWebTextStyle(): SwiftTUITextStyle =
  SwiftTUITextStyle(
    foregroundColor = webColor("fg"),
    backgroundColor = webColor("bg"),
    emphasis = emphasisTokens(optInt("em", 0)),
    underlineStyle = optJSONObject("underline")?.toWebTextLineStyle(),
    strikethroughStyle = optJSONObject("strikethrough")?.toWebTextLineStyle(),
    opacity = optDouble("opacity", 1.0)
  )

private fun JSONObject.toWebTextLineStyle(): SwiftTUITextLineStyle =
  SwiftTUITextLineStyle(
    pattern = optString("pattern", "solid"),
    color = webColor("color")
  )

private fun JSONObject.toWebTerminalStyle(): SwiftTUITerminalStyle =
  SwiftTUITerminalStyle(
    foregroundColor = keyedColor("foregroundColor")
      ?: SwiftTUITerminalStyle.Default.foregroundColor,
    backgroundColor = keyedColor("backgroundColor")
      ?: SwiftTUITerminalStyle.Default.backgroundColor,
    tintColor = keyedColor("tintColor") ?: SwiftTUITerminalStyle.Default.tintColor
  )

private fun JSONObject.toWebImageAttachment(): SwiftTUIImageAttachment =
  SwiftTUIImageAttachment(
    id = optString("id"),
    bounds = optJSONArray("bounds").toWebRect(),
    visibleBounds = optJSONArray("visibleBounds").toWebRect(),
    sourceKind = "webSurface",
    sourceIdentifier = null,
    // Transmit-once payloads: absence means the id was already delivered;
    // the renderer's id-keyed bitmap cache serves the repeat.
    payloadBase64 = optionalStringWeb("dataBase64"),
    payloadByteCount = null,
    pixelSize = optJSONArray("pixelSize").toWebPixelSize(),
    cellPixelSize = null,
    isResizable = false,
    scalingMode = optString("scalingMode", "stretch"),
    opacity = normalizedSwiftTUIImageOpacity(optDouble("opacity", 1.0))
  )

private fun JSONObject.toWebAccessibilityNode(): SwiftTUIAccessibilityNode =
  SwiftTUIAccessibilityNode(
    id = optString("id"),
    parentID = optionalStringWeb("parentId"),
    rect = optJSONArray("rect").toWebRect(),
    role = optString("role", "group"),
    label = optionalStringWeb("label"),
    hint = optionalStringWeb("hint"),
    hidden = optBoolean("hidden"),
    liveRegion = optionalStringWeb("liveRegion"),
    cursorAnchor = optJSONArray("cursorAnchor")?.toWebPoint(),
    isFocused = optBoolean("isFocused")
  )

private fun JSONObject.toWebScrollRegion(): SwiftTUIScrollRegion =
  SwiftTUIScrollRegion(
    id = optString("id"),
    rect = optJSONArray("rect").toWebRect(),
    offset = optJSONArray("offset")?.toWebPoint() ?: SwiftTUIPoint(x = 0, y = 0),
    content = optJSONArray("content")?.toWebCellSize()
      ?: SwiftTUICellSize(width = 0, height = 0)
  )

private fun JSONObject.toWebFocusPresentation(): SwiftTUIFocusPresentation =
  SwiftTUIFocusPresentation(
    focusedIdentity = optionalStringWeb("focusedIdentity"),
    semantics = optString("semantics", "none"),
    prefersTextInput = optBoolean("prefersTextInput"),
    hasFocusedRegion = optBoolean("hasFocusedRegion")
  )

private fun JSONArray?.toWebRect(): SwiftTUIRect =
  this?.let {
    SwiftTUIRect(x = optInt(0), y = optInt(1), width = optInt(2), height = optInt(3))
  } ?: SwiftTUIRect(x = 0, y = 0, width = 0, height = 0)

private fun JSONArray.toWebPoint(): SwiftTUIPoint =
  SwiftTUIPoint(x = optInt(0), y = optInt(1))

private fun JSONArray?.toWebPixelSize(): SwiftTUIPixelSize? =
  this?.let { SwiftTUIPixelSize(width = optInt(0), height = optInt(1)) }

private fun JSONArray?.toWebCellSize(): SwiftTUICellSize =
  this?.let { SwiftTUICellSize(width = optInt(0), height = optInt(1)) }
    ?: SwiftTUICellSize(width = 0, height = 0)

/** Web color values are bare `#RRGGBBAA` strings. */
private fun JSONObject.webColor(name: String): SwiftTUIColor? =
  optionalStringWeb(name)?.let { SwiftTUIColor(it) }

/** Terminal-style colors keep the keyed `{"hex": …}` object shape. */
private fun JSONObject.keyedColor(name: String): SwiftTUIColor? =
  optJSONObject(name)?.optionalStringWeb("hex")?.let { SwiftTUIColor(it) }

private fun JSONObject.optionalStringWeb(name: String): String? =
  if (has(name) && !isNull(name)) optString(name) else null

private fun JSONObject.optionalIntWeb(name: String): Int? =
  if (has(name) && !isNull(name)) optInt(name) else null

private fun JSONObject.optionalSafeWireInteger(name: String): Long? {
  if (!has(name)) {
    return null
  }
  val value = get(name)
  require(value is Number) { "web-surface $name must be an integer" }
  val asDouble = value.toDouble()
  require(
    asDouble.isFinite() &&
      asDouble >= -MAX_SAFE_WIRE_INTEGER.toDouble() &&
      asDouble <= MAX_SAFE_WIRE_INTEGER.toDouble() &&
      asDouble % 1.0 == 0.0
  ) {
    "web-surface $name must be a safe integer"
  }
  return asDouble.toLong()
}

private fun JSONArray?.objects(): List<JSONObject> = buildList {
  val array = this@objects ?: return@buildList
  for (index in 0 until array.length()) {
    array.optJSONObject(index)?.let(::add)
  }
}

private const val MAX_SAFE_WIRE_INTEGER = 9_007_199_254_740_991L

private fun JSONArray?.strings(): List<String> = buildList {
  val array = this@strings ?: return@buildList
  for (index in 0 until array.length()) {
    add(array.optString(index))
  }
}
