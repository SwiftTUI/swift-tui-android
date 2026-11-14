package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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
class SwiftTUIWebSurfaceSession internal constructor(
  internal val rowDecoding: SwiftTUIRowDecoding
) {
  constructor() : this(SwiftTUIRowDecoding.current())

  private data class CellTuple(
    val x: Int,
    val text: String,
    val span: Int,
    val styleIndex: Int
  )

  /**
   * One row's decoded cells, kept with what they were decoded from: the wire
   * row (by identity — a delta replaces a row's list, it never edits one), the
   * link runs applied to it, and the highest style slot it reads.
   */
  private class RetainedRow(
    val tuples: List<CellTuple>,
    val cells: SwiftTUIDecodedRow,
    val links: RowLinks?,
    val maxStyleIndex: Int
  )

  /** The link runs of one row in wire order, with their targets resolved. */
  private class RowLinks(capacity: Int) {
    private var starts = IntArray(capacity)
    private var spans = IntArray(capacity)
    private var targets = arrayOfNulls<String>(capacity)
    var count = 0
      private set

    fun add(start: Int, span: Int, target: String) {
      if (count == starts.size) {
        val grown = maxOf(4, count * 2)
        starts = starts.copyOf(grown)
        spans = spans.copyOf(grown)
        targets = targets.copyOf(grown)
      }
      starts[count] = start
      spans[count] = span
      targets[count] = target
      count += 1
    }

    /** The target of the last run covering column [x]: a later run overrides an earlier one. */
    fun targetAt(x: Int): String? {
      for (index in count - 1 downTo 0) {
        val start = starts[index]
        if (x >= start && x < start + spans[index]) return targets[index]
      }
      return null
    }

    fun sameRuns(other: RowLinks): Boolean {
      if (count != other.count) return false
      for (index in 0 until count) {
        if (starts[index] != other.starts[index] || spans[index] != other.spans[index] ||
          targets[index] != other.targets[index]) return false
      }
      return true
    }
  }

  private class DecodedCells(val cells: List<SwiftTUICell>, val retained: Array<RetainedRow>?)

  private var baselineRows: List<List<CellTuple>>? = null
  /**
   * The decoded rows of the active baseline, parallel to [baselineRows], and
   * the style table they were resolved against. Held only on the retained
   * path, and only for the one baseline: a row a delta replaces is dropped
   * here and lives on only in frames that were published with it.
   */
  private var retainedRows: Array<RetainedRow>? = null
  private var retainedStyles: List<SwiftTUITextStyle?> = emptyList()
  /**
   * The retained style table. Held across records because a delta may carry
   * only the styles it added, keyed by `stylesBase` — the negotiated
   * `styleAppend` shape.
   */
  private var baselineStyles: List<SwiftTUITextStyle?> = emptyList()
  private var baselineWidth = 0
  private var baselineHeight = 0
  internal var lastEpoch: Long? = null
    private set
  private var lastGeneration: Long? = null
  private var consumedGeneration = 0L

  internal var pendingResyncScope: String? = null
    private set

  fun reset() {
    baselineRows = null
    retainedRows = null
    retainedStyles = emptyList()
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

  /** The decoded rows this session retains, or `null` when it retains none. */
  internal fun retainedRowCells(): List<List<SwiftTUICell>>? = retainedRows?.map { it.cells }

  /**
   * Decodes one web-surface record into a frame, or `null` when the record
   * cannot be applied (a delta without a usable baseline — the Swift host
   * re-keyframes on size changes and declarations, so this is a transient
   * guard, not an error path). A record declaring a newer version than
   * [SUPPORTED_WEB_SURFACE_VERSION] fails loudly (the F57 skew guard).
   */
  fun decode(payload: String): SwiftTUIFrame? = decodeRecord(payload, validatedUTF8Bytes = -1)

  // Only the native poller supplies this count, after strict UTF-8 admission.
  // Direct String callers still receive the complete UTF-8 allocation check.
  internal fun decodeValidatedUTF8(payload: String, byteCount: Int): SwiftTUIFrame? {
    require(byteCount >= 0)
    return decodeRecord(payload, validatedUTF8Bytes = byteCount)
  }

  private fun decodeRecord(payload: String, validatedUTF8Bytes: Int): SwiftTUIFrame? {
    require(payload.startsWith(RECORD_PREFIX)) { "not a web-surface record" }
    val byteLimit = SwiftTUIWireBudget.RECORD_BYTES + if (payload.endsWith('\n')) 1 else 0
    val fitsBudget = if (validatedUTF8Bytes >= 0) validatedUTF8Bytes <= byteLimit
      else SwiftTUIWireBudget.fitsUTF8(payload, byteLimit)
    if (!fitsBudget) {
      return refuseDelta(shouldRequestKeyframe = true)
    }
    // Scan exactly the JSON text that org.json parses; the record name is not JSON.
    if (!SwiftTUIWireBudget.admitsJSON(payload, RECORD_PREFIX.length)) {
      return refuseDelta(shouldRequestKeyframe = true)
    }
    // Advance over the ASCII record name without copying the complete payload.
    // The scanner and parser both retain the same trailing JSON whitespace.
    val tokens = JSONTokener(payload)
    repeat(RECORD_PREFIX.length) { tokens.next() }
    val record = JSONObject(tokens)
    val version = record.optInt("version", 0)
    require(version <= SUPPORTED_WEB_SURFACE_VERSION) {
      "web-surface version $version is newer than the supported " +
        "$SUPPORTED_WEB_SURFACE_VERSION; update the swift-tui-android host library."
    }
    if (version in 1..3 && !SwiftTUIWireBudget.surface(
        record, record.requiredGridDimension("width"), record.requiredGridDimension("height"))) {
      return refuseDelta(shouldRequestKeyframe = true)
    }
    return when {
      version == 1 || version == 2 -> decodeFull(record)
      version == 3 && record.optionalStringWeb("encoding") == "delta" -> decodeDelta(record)
      else -> null
    }
  }

  private fun decodeFull(record: JSONObject): SwiftTUIFrame {
    val width = record.requiredGridDimension("width")
    val height = record.requiredGridDimension("height")
    val stamp = record.fullFrameStamp()
    val rows = parseRowTuples(record.optJSONArray("rows"))
    val styles = parseStyleTable(record.optJSONArray("styles"))
    val decoded = decodeCells(record, rows, styles)
    val frame = buildFrame(record, decoded.cells)
    baselineRows = rows
    baselineStyles = styles
    retain(decoded, styles)
    baselineWidth = width
    baselineHeight = height
    lastEpoch = stamp.epoch
    lastGeneration = stamp.generation
    pendingResyncScope = null
    return frame
  }

  private fun decodeDelta(record: JSONObject): SwiftTUIFrame? {
    val width = record.requiredGridDimension("width")
    val height = record.requiredGridDimension("height")
    // Validate the complete optional stamp tuple before consulting or mutating
    // session state. A malformed record is structural failure even while a
    // prior keyframe request is outstanding.
    val stamp = record.deltaFrameStamp()
    if (pendingResyncScope != null) {
      return null
    }
    val carriesBaselineStamp = stamp.epoch != null
    val baseline = baselineRows ?: return refuseDelta(carriesBaselineStamp)
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
      if (row < 0 || row >= height) {
        return refuseDelta(carriesBaselineStamp)
      }
      // A full frame may omit trailing empty rows, so an in-grid row can lie
      // past the retained arrays. Materialize those omitted rows as empty
      // (bounded by the admitted height) before replacing this one.
      while (rows.size <= row) {
        rows.add(emptyList())
      }
      rows[row] = parseCellTuples(row, entry.optJSONArray(1))
    }
    val decoded = decodeCells(record, rows, styles)
    val frame = buildFrame(record, decoded.cells)
    baselineRows = rows
    baselineStyles = styles
    retain(decoded, styles)
    lastEpoch = stamp.epoch
    lastGeneration = stamp.generation
    return frame
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

  /** Commits the decoded rows with the baseline they belong to. */
  private fun retain(decoded: DecodedCells, styles: List<SwiftTUITextStyle?>) {
    if (decoded.retained != null) {
      retainedRows = decoded.retained
      retainedStyles = styles
    }
  }

  private fun decodeCells(
    record: JSONObject,
    rowTuples: List<List<CellTuple>>,
    styles: List<SwiftTUITextStyle?>
  ): DecodedCells = when (rowDecoding) {
    SwiftTUIRowDecoding.Rebuild -> DecodedCells(rebuildCells(record, rowTuples, styles), retained = null)
    SwiftTUIRowDecoding.RetainedRows -> retainCells(record, rowTuples, styles)
  }

  /**
   * Decodes only the rows whose cells could differ from the retained ones: a
   * row the record replaced, a row whose link runs changed, and a row that
   * reads a style slot the new table no longer resolves to an equal style.
   * Reads session state but does not change it; [retain] commits the result
   * once the frame is built.
   */
  private fun retainCells(
    record: JSONObject,
    rowTuples: List<List<CellTuple>>,
    styles: List<SwiftTUITextStyle?>
  ): DecodedCells {
    val links = parseRowLinks(
      record.optJSONArray("links"), record.optJSONArray("linkTargets").strings(), rowTuples.size)
    val previous = retainedRows
    val stableStyles = if (previous == null) 0 else stableStylePrefix(retainedStyles, styles)
    val rows = Array(rowTuples.size) { y ->
      val tuples = rowTuples[y]
      val rowLinks = links?.get(y)
      val candidate = if (previous != null && y < previous.size) previous[y] else null
      if (candidate != null && candidate.tuples === tuples &&
        candidate.maxStyleIndex < stableStyles && sameLinks(candidate.links, rowLinks)) {
        candidate
      } else {
        decodeRow(y, tuples, styles, rowLinks)
      }
    }
    // The frame gets its own array of rows; the rows themselves are shared and never mutated.
    return DecodedCells(SwiftTUIRowCells(Array(rows.size) { rows[it].cells }), rows)
  }

  private fun decodeRow(
    y: Int,
    tuples: List<CellTuple>,
    styles: List<SwiftTUITextStyle?>,
    links: RowLinks?
  ): RetainedRow {
    var maxStyleIndex = -1
    val cells = Array(tuples.size) { index ->
      val tuple = tuples[index]
      if (tuple.styleIndex > maxStyleIndex) maxStyleIndex = tuple.styleIndex
      SwiftTUICell(
        x = tuple.x,
        y = y,
        character = tuple.text,
        spanWidth = tuple.span,
        continuationLeadX = null,
        style = styles.getOrNull(tuple.styleIndex),
        hyperlink = links?.targetAt(tuple.x)
      )
    }
    return RetainedRow(tuples, SwiftTUIDecodedRow(cells), links, maxStyleIndex)
  }

  /**
   * How many leading style slots resolve to an equal style in both tables. A
   * row whose highest slot is below this resolves exactly as it did. Equal
   * tables keep every row, including one that reads a slot past the end.
   */
  private fun stableStylePrefix(
    retained: List<SwiftTUITextStyle?>,
    next: List<SwiftTUITextStyle?>
  ): Int {
    val common = minOf(retained.size, next.size)
    var index = 0
    while (index < common) {
      val before = retained[index]
      val after = next[index]
      if (before !== after && before != after) break
      index += 1
    }
    return if (index == retained.size && index == next.size) Int.MAX_VALUE else index
  }

  private fun sameLinks(retained: RowLinks?, next: RowLinks?): Boolean =
    if (retained == null || next == null) retained === next else retained.sameRuns(next)

  /**
   * The record's link runs by row, or `null` when it links nothing. Mirrors
   * [applyLinkRuns]: a run whose target does not resolve is skipped, and a
   * later run overrides an earlier one on the columns they share.
   */
  private fun parseRowLinks(
    linkRuns: JSONArray?,
    linkTargets: List<String>,
    rowCount: Int
  ): Array<RowLinks?>? {
    val runs = linkRuns ?: return null
    if (runs.length() == 0 || linkTargets.isEmpty()) {
      return null
    }
    var byRow: Array<RowLinks?>? = null
    for (index in 0 until runs.length()) {
      val entry = runs.optJSONArray(index) ?: continue
      val y = entry.optInt(0, -1)
      val rowRuns = entry.optJSONArray(1) ?: continue
      // A row without retained cells has no cell to link.
      if (y < 0 || y >= rowCount) continue
      for (runIndex in 0 until rowRuns.length()) {
        val run = rowRuns.optJSONArray(runIndex) ?: continue
        val start = run.optInt(0)
        val span = run.optInt(1)
        val target = linkTargets.getOrNull(run.optInt(2, -1)) ?: continue
        // An empty or overflowing run covers no column.
        if (start + span <= start) continue
        val rows = byRow ?: arrayOfNulls<RowLinks>(rowCount).also { byRow = it }
        val row = rows[y] ?: RowLinks(rowRuns.length()).also { rows[y] = it }
        row.add(start, span, target)
      }
    }
    return byRow
  }

  /** The [SwiftTUIRowDecoding.Rebuild] path: every cell and every link, every frame. */
  private fun rebuildCells(
    record: JSONObject,
    rowTuples: List<List<CellTuple>>,
    styles: List<SwiftTUITextStyle?>
  ): List<SwiftTUICell> {
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
    return applyLinkRuns(cells, linkRuns, linkTargets)
  }

  private fun buildFrame(record: JSONObject, cells: List<SwiftTUICell>): SwiftTUIFrame {
    val damage = record.optJSONObject("damage")
    val textDamageRows = parseDamageTextRows(damage?.optJSONArray("textRows"))
    val focusPresentation = record.optJSONObject("focusPresentation")
      ?.toWebFocusPresentation() ?: SwiftTUIFocusPresentation.None

    val nextConsumedGeneration = consumedGeneration + 1
    return SwiftTUIFrame(
      sequence = record.optLong("sequence", 0L),
      // Contiguous per decoded frame: the converged wire's damage is
      // consumption-relative (the Swift host accumulates it across skipped
      // polls), so partial repaints stay legal across sequence gaps.
      consumedGeneration = nextConsumedGeneration,
      gridWidth = record.optInt("width"),
      gridHeight = record.optInt("height"),
      preferredGridWidth = record.optionalIntWeb("preferredGridWidth"),
      preferredGridHeight = record.optionalIntWeb("preferredGridHeight"),
      terminalStyle = record.optJSONObject("terminalStyle")?.toWebTerminalStyle()
        ?: SwiftTUITerminalStyle.Default,
      cells = cells,
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
      requiresFullGraphicsReplay = damage?.optBoolean("requiresFullGraphicsReplay", true) ?: true,
      accessibilityActionResponse = record.optJSONObject("accessibilityActionResponse")?.toAccessibilityActionResponse()
    ).also { consumedGeneration = nextConsumedGeneration }
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
    isFocused = optBoolean("isFocused"),
    actionTarget = optionalStringWeb("actionTarget"),
    actions = optJSONArray("actions")?.let { a ->
      (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet()
    } ?: emptySet(),
    isEnabled = optBoolean("isEnabled", true),
    value = if (optString("role") == "secureField") null else optJSONObject("value")?.toAccessibilityValue(),
    valueMin = (opt("valueMin") as? Number)?.toDouble()?.takeIf { it.isFinite() },
    valueMax = (opt("valueMax") as? Number)?.toDouble()?.takeIf { it.isFinite() },
    valueStep = (opt("valueStep") as? Number)?.toDouble()?.takeIf { it.isFinite() },
    textSelection = if (optString("role") == "secureField") null else optJSONArray("textSelection")?.toWebTextSelection()
  )

/** `[anchor, head]`: exactly two nonnegative integers, or no selection at all. */
private fun JSONArray.toWebTextSelection(): SwiftTUITextSelection? {
  if (length() != HostContract.TEXT_RANGE_ARITY) return null
  val anchor = (opt(0) as? Int) ?: return null
  val head = (opt(1) as? Int) ?: return null
  if (anchor < 0 || head < 0) return null
  return SwiftTUITextSelection(anchor = anchor, head = head)
}

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

private fun JSONObject.requiredGridDimension(name: String): Int {
  val value = get(name)
  require(value is Number) { "web-surface $name must be a nonnegative Int dimension" }
  val dimension = runCatching { value.toString().toBigDecimal().intValueExact() }.getOrNull()
  require(dimension != null && dimension >= 0) {
    "web-surface $name must be a nonnegative Int dimension"
  }
  return dimension
}

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
