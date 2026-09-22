package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONObject

/** Fixed cross-host limits, mirrored by Swift and the browser runtime. */
internal object SwiftTUIWireBudget {
  const val RECORD_BYTES = 4 * 1024 * 1024 // RS and prefix included; terminal LF excluded.
  const val GRID_DIMENSION = 1024
  const val GRID_CELLS = 65536
  const val IMAGES = 1024
  const val METADATA_ENTRIES = 65536
  const val CELL_TEXT_BYTES = 256
  const val STYLE_BYTES = 1024
  const val JSON_DEPTH = 32
  const val RASTER_DIMENSION = 8192
  const val RASTER_PIXELS = 16 * 1024 * 1024

  fun imageSize(width: Int, height: Int): Boolean =
    width in 1..RASTER_DIMENSION && height in 1..RASTER_DIMENSION &&
      width.toLong() * height <= RASTER_PIXELS

  fun grid(width: Int, height: Int): Boolean =
    width in 0..GRID_DIMENSION && height in 0..GRID_DIMENSION &&
      width.toLong() * height <= GRID_CELLS

  fun fitsUTF8(text: String, limit: Int): Boolean {
    var bytes = 0
    var index = 0
    while (index < text.length) {
      val c = text[index++]
      bytes += when {
        c.code <= 0x7f -> 1
        c.code <= 0x7ff -> 2
        c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
          index++
          4
        }
        else -> 3
      }
      if (bytes > limit) return false
    }
    return true
  }

  fun depth(text: String): Boolean {
    var depth = 0
    var quoted = false
    var escaped = false
    for (c in text) {
      if (quoted) {
        if (escaped) escaped = false
        else if (c == '\\') escaped = true
        else if (c == '"') quoted = false
      } else if (c == '"') quoted = true
      else if (c == '[' || c == '{') {
        if (++depth > JSON_DEPTH) return false
      } else if (c == ']' || c == '}') depth--
    }
    return true
  }

  // Eight units per JSON value, plus UTF-8 bytes in keys and string values.
  private fun styleContent(style: Any): Boolean {
    var remaining = STYLE_BYTES
    fun chargeString(text: String): Boolean {
      var index = 0
      while (index < text.length) {
        val c = text[index++]
        remaining -= when {
          c.code <= 0x7f -> 1
          c.code <= 0x7ff -> 2
          c.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> { index++; 4 }
          else -> 3
        }
        if (remaining < 0) return false
      }
      return true
    }
    fun visit(value: Any): Boolean {
      remaining -= 8
      if (remaining < 0) return false
      when (value) {
        is String -> return chargeString(value)
        is JSONArray -> for (index in 0 until value.length()) { if (!visit(value.get(index))) return false }
        is JSONObject -> for (key in value.keys()) { if (!chargeString(key) || !visit(value.get(key))) return false }
      }
      return true
    }
    return visit(style)
  }

  fun surface(record: JSONObject, width: Int, height: Int): Boolean {
    if (!grid(width, height)) return false
    val styles = record.optJSONArray("styles") ?: return false
    val base = if (record.has("stylesBase")) record.exactInt("stylesBase") ?: return false else 0
    val styleCount = styles.length().toLong() + base
    if (base < 0 || styleCount > maxOf(1024, width * height + 1)) return false
    for (index in 0 until styles.length()) {
      if (!styleContent(styles.get(index))) return false
    }
    fun cells(cells: JSONArray): Boolean {
      if (cells.length() > width) return false
      var end = 0
      for (index in 0 until cells.length()) {
        val cell = cells.optJSONArray(index) ?: return false
        val x = cell.exactInt(0) ?: return false
        val span = cell.exactInt(2) ?: return false
        val style = cell.exactInt(3) ?: return false
        val text = cell.opt(1) as? String ?: return false
        if (x < end || span < 1 || x.toLong() + span > width || style < 0 || style >= styleCount ||
          !fitsUTF8(text, CELL_TEXT_BYTES)) return false
        end = x + span
      }
      return true
    }
    record.optJSONArray("rows")?.let { rows ->
      if (rows.length() > height) return false
      for (index in 0 until rows.length()) {
        if (!cells(rows.optJSONArray(index) ?: return false)) return false
      }
    }
    record.optJSONArray("deltaRows")?.let { rows ->
      if (rows.length() > height) return false
      val seen = mutableSetOf<Int>()
      for (index in 0 until rows.length()) {
        val entry = rows.optJSONArray(index) ?: return false
        val y = entry.exactInt(0) ?: return false
        if (y !in 0 until height || !seen.add(y) ||
          !cells(entry.optJSONArray(1) ?: return false)) return false
      }
    }
    if ((record.optJSONArray("images")?.length() ?: 0) > IMAGES) return false
    record.optJSONArray("images")?.let { images ->
      for (index in 0 until images.length()) {
        val image = images.optJSONObject(index) ?: return false
        if (!fitsUTF8(image.optString("id"), 1024)) return false
        image.optJSONArray("pixelSize")?.let { size ->
          val w = size.exactInt(0) ?: return false
          val h = size.exactInt(1) ?: return false
          if (w !in 0..RASTER_DIMENSION || h !in 0..RASTER_DIMENSION || w.toLong() * h > RASTER_PIXELS)
            return false
        }
      }
    }
    for (key in listOf("accessibilityTree", "accessibilityAnnouncements", "scrollRegions", "linkTargets")) {
      if ((record.optJSONArray(key)?.length() ?: 0) > METADATA_ENTRIES) return false
    }
    for (key in listOf("preferredGridWidth", "preferredGridHeight")) {
      if (record.has(key) && (record.exactInt(key) ?: return false) !in 0..GRID_DIMENSION) return false
    }
    if (!grid(record.optInt("preferredGridWidth", 0), record.optInt("preferredGridHeight", 0))) return false
    for (rows in listOf(record.optJSONArray("links"), record.optJSONObject("damage")?.optJSONArray("textRows"))) {
      if (rows == null) continue
      if (rows.length() > height) return false
      val seen = mutableSetOf<Int>()
      for (index in 0 until rows.length()) {
        val entry = rows.optJSONArray(index) ?: return false
        val y = entry.exactInt(0) ?: return false
        val ranges = entry.optJSONArray(1) ?: return false
        if (y !in 0 until height || !seen.add(y) || ranges.length() > width) return false
        var end = 0L
        for (rangeIndex in 0 until ranges.length()) {
          val range = ranges.optJSONArray(rangeIndex) ?: return false
          val start = range.exactInt(0)?.toLong() ?: return false
          val value = range.exactInt(1)?.toLong() ?: return false
          val stop = if (range.length() == 3) start + value else value
          if (start < 0 || (range.length() == 3 && start < end) || stop < start || stop > width) return false
          end = stop
        }
      }
    }
    return true
  }
}

private fun exactInt(value: Any?): Int? = if (value is Number) {
  val number = value.toDouble()
  if (number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number % 1.0 == 0.0)
    number.toInt() else null
} else null

private fun JSONObject.exactInt(key: String): Int? = exactInt(opt(key))
private fun JSONArray.exactInt(index: Int): Int? = exactInt(opt(index))
