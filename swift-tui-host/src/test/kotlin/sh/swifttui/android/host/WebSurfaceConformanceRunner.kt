package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android's executable S5 adapter. It intentionally uses the production
 * decoder session, frame poller, and bitmap-cache policy; the only fake is the
 * native size/copy/request seam around them.
 */
internal class WebSurfaceConformanceRunner {
  private var native = RunnerNative()
  private var cache = RunnerBitmapCache()
  private var currentFrame: SwiftTUIFrame? = null
  private var visibleImageIDs = sortedSetOf<String>()

  fun run(fixture: WebSurfaceConformanceFixture) {
    require(fixture.entry.kind == "record")
    require(WebSurfaceConformanceLoader.RUNNER_ID in fixture.entry.runners)

    fixture.steps.forEachIndexed { index, step ->
      when {
        step.has("emit") -> {
          if (index !in fixture.droppedEmitIndices) {
            emit(step.getString("emit"))
          }
        }
        step.has("drop") -> Unit
        step.has("evictImages") -> {
          val ids = step.getJSONArray("evictImages")
          for (idIndex in 0 until ids.length()) {
            cache.evict(ids.getString(idIndex))
          }
        }
        step.has("reconnect") -> reconnect()
        step.has("expect") -> {
          val expected = step.getJSONObject("expect")
          val actual = observation(includeStyleRuns = expected.has("styleRuns"))
          WebSurfaceConformanceLoader.assertJSONObjectEquals(expected, actual)
          native.consumeResyncRequests()
        }
        else -> error("validated fixture contains an unsupported Android action")
      }
    }
  }

  internal fun observation(includeStyleRuns: Boolean): JSONObject = JSONObject().apply {
    put("rows", structuredRows())
    put("imagesVisible", JSONArray(visibleImageIDs.toList()))
    put(
      "resyncRequests",
      JSONArray(
        native.peekResyncRequests().map {
          parseExactJSONObject(it, "Android resync request")
        }
      )
    )
    if (includeStyleRuns) {
      put("styleRuns", styleRuns())
    }
  }

  private fun emit(record: String) {
    native.enqueue(record)
    when (val result = native.poller.poll(HANDLE)) {
      SwiftTUIFramePollResult.None -> Unit
      is SwiftTUIFramePollResult.Error -> error("Android conformance decode failed: ${result.message}")
      is SwiftTUIFramePollResult.Frame -> {
        if (result.shouldPublishOver(currentFrame)) {
          currentFrame = result.value
          repaint()
        }
      }
    }
  }

  private fun repaint() {
    val missing = linkedSetOf<String>()
    val visible = sortedSetOf<String>()
    for (attachment in currentFrame?.imageAttachments.orEmpty()) {
      when (
        SwiftTUIBitmapCachePolicy.resolve(
          key = attachment.id,
          cached = { cache.get(attachment.id) },
          payload = attachment.payloadBase64,
          decode = { payload -> cache.decode(attachment.id, payload) },
          maxSize = { cache.maxSize },
          sizeOf = { it.size },
          put = cache::put
        )
      ) {
        is SwiftTUIBitmapResolution.Drawable -> visible += attachment.id
        SwiftTUIBitmapResolution.MissingPayload -> missing += attachment.id
        SwiftTUIBitmapResolution.InvalidPayload -> Unit
      }
    }
    visibleImageIDs = visible
    native.poller.reportMissingImagePayloads(missing)
    // Exercise the real no-frame path that flushes image/keyframe requests.
    if (missing.isNotEmpty()) {
      check(native.poller.poll(HANDLE) == SwiftTUIFramePollResult.None)
    }
  }

  private fun reconnect() {
    val retainedRequests = native.peekResyncRequests()
    native = RunnerNative(retainedRequests)
    cache = RunnerBitmapCache()
    currentFrame = null
    visibleImageIDs = sortedSetOf()
  }

  private fun structuredRows(): JSONArray {
    val rows = JSONArray()
    currentFrame?.cells
      .orEmpty()
      .filterNot { it.isContinuation }
      .groupBy { it.y }
      .toSortedMap()
      .forEach { (rowIndex, rowCells) ->
        val cells = JSONArray()
        rowCells.sortedBy { it.x }.forEach { cell ->
          cells.put(
            JSONObject()
              .put("column", cell.x)
              .put("text", cell.character)
              .put("span", cell.spanWidth)
          )
        }
        rows.put(JSONObject().put("row", rowIndex).put("cells", cells))
      }
    return rows
  }

  private fun styleRuns(): JSONArray {
    data class MutableRun(
      val row: Int,
      val startColumn: Int,
      var text: String,
      var span: Int,
      val style: SwiftTUITextStyle
    )

    val completed = mutableListOf<MutableRun>()
    var active: MutableRun? = null
    currentFrame?.cells
      .orEmpty()
      .filter { !it.isContinuation && it.style != null }
      .sortedWith(compareBy<SwiftTUICell> { it.y }.thenBy { it.x })
      .forEach { cell ->
        val style = requireNotNull(cell.style)
        val previous = active
        if (
          previous != null &&
          previous.row == cell.y &&
          previous.startColumn.toLong() + previous.span.toLong() == cell.x.toLong() &&
          previous.style == style
        ) {
          previous.text += cell.character
          previous.span += cell.spanWidth
        } else {
          previous?.let(completed::add)
          active = MutableRun(
            row = cell.y,
            startColumn = cell.x,
            text = cell.character,
            span = cell.spanWidth,
            style = style
          )
        }
      }
    active?.let(completed::add)

    return JSONArray(completed.map { run ->
      JSONObject()
        .put("row", run.row)
        .put("startColumn", run.startColumn)
        .put("text", run.text)
        .put("span", run.span)
        .put("resolvedStyle", run.style.toConformanceJSON())
    })
  }

  private class RunnerNative(
    retainedRequests: List<String> = emptyList()
  ) {
    private val records = ArrayDeque<ByteArray>()
    private val resyncRequests = retainedRequests.toMutableList()

    val poller = SwiftTUIFramePoller(
      session = SwiftTUIWebSurfaceSession(),
      copyLatestFrame = ::copyLatestFrame,
      requestResync = ::requestResync
    )

    fun enqueue(record: String) {
      records.addLast(record.encodeToByteArray())
    }

    fun peekResyncRequests(): List<String> = resyncRequests.toList()

    fun consumeResyncRequests(): List<String> =
      resyncRequests.toList().also { resyncRequests.clear() }

    private fun copyLatestFrame(
      @Suppress("UNUSED_PARAMETER") handle: Long,
      destination: ByteArray?,
      capacity: Int
    ): Int {
      val bytes = records.firstOrNull() ?: return 0
      if (destination == null || capacity <= 0) return bytes.size
      if (capacity < bytes.size) return bytes.size
      bytes.copyInto(destination)
      records.removeFirst()
      return bytes.size
    }

    private fun requestResync(
      @Suppress("UNUSED_PARAMETER") handle: Long,
      request: ByteArray,
      count: Int
    ): Int {
      resyncRequests += request.decodeToString(0, count)
      return 1
    }
  }

  private data class RunnerBitmap(val id: String, val size: Int)

  private class RunnerBitmapCache {
    val maxSize = 1_024
    private val entries = linkedMapOf<String, RunnerBitmap>()

    fun get(id: String): RunnerBitmap? = entries[id]

    fun decode(id: String, payload: String): RunnerBitmap? =
      payload.takeIf(String::isNotEmpty)?.let { RunnerBitmap(id, 1) }

    fun put(id: String, bitmap: RunnerBitmap) {
      entries[id] = bitmap
    }

    fun evict(id: String) {
      entries.remove(id)
    }
  }

  private companion object {
    const val HANDLE = 707L
  }
}

private fun SwiftTUITextStyle.toConformanceJSON(): JSONObject = JSONObject().apply {
  foregroundColor?.let { put("fg", it.hex) }
  backgroundColor?.let { put("bg", it.hex) }
  val emphasisMask = emphasis.fold(0) { result, token ->
    result or when (token) {
      "bold" -> 1 shl 0
      "italic" -> 1 shl 1
      "faint" -> 1 shl 2
      "blink" -> 1 shl 3
      "reverse" -> 1 shl 4
      else -> 0
    }
  }
  if (emphasisMask != 0) put("em", emphasisMask)
  underlineStyle?.let { put("underline", it.toConformanceJSON()) }
  strikethroughStyle?.let { put("strikethrough", it.toConformanceJSON()) }
  if (opacity != 1.0) put("opacity", opacity)
}

private fun SwiftTUITextLineStyle.toConformanceJSON(): JSONObject = JSONObject().apply {
  put("pattern", pattern)
  color?.let { put("color", it.hex) }
}
