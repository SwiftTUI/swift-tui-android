package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONObject

/** Limits generated from the public contract schema. */
internal object SwiftTUIWireBudget {
  const val RECORD_BYTES = HostContract.RECORD_BYTES
  const val GRID_DIMENSION = HostContract.GRID_DIMENSION
  const val GRID_CELLS = HostContract.GRID_CELLS
  const val IMAGES = HostContract.IMAGES
  const val METADATA_ENTRIES = HostContract.METADATA_ENTRIES
  const val CELL_TEXT_BYTES = HostContract.CELL_TEXT_BYTES
  const val STYLE_BYTES = HostContract.STYLE_BYTES
  const val JSON_DEPTH = HostContract.JSON_DEPTH
  const val RASTER_DIMENSION = HostContract.RASTER_DIMENSION
  const val RASTER_PIXELS = HostContract.RASTER_PIXELS

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

  /**
   * Admission of a JSON payload before org.json parses it: the RFC 8259
   * grammar, plus nesting depth. org.json is lenient. It would read non-JSON
   * runs such as `NaN`, `Infinity`, `+1`, `0x10`, `TRUE` or unquoted words as
   * values, take a numeric or literal key as text, read an elided array
   * element as null, drop a trailing `,` and ignore anything after the first
   * value. Browser `JSON.parse` rejects all of these. The scan therefore
   * requires string keys, one `:` after each key, one `,` between members
   * and between elements, closers that match, and one top-level value
   * followed only by JSON whitespace, with at most [JSON_DEPTH] levels.
   * Every bare run must be `true`, `false`, `null` or an RFC 8259 number,
   * delimited by structural characters, a quote or the four JSON whitespace
   * characters. Binary64 overflow is rejected even in unknown fields.
   * org.json validates string escapes and rejects duplicate object keys.
   *
   * The scan fails at the first token no JSON text can hold at that point.
   * A payload that ends before its value closes passes, and org.json then
   * rejects it as malformed.
   */
  fun admitsJSON(text: String, startIndex: Int = 0): Boolean {
    if (startIndex !in 0..text.length) return false
    val objects = BooleanArray(JSON_DEPTH) // Per open container: object (true) or array.
    var depth = 0
    var next = VALUE
    var index = startIndex
    while (index < text.length) {
      val c = text[index]
      when {
        c == ' ' || c == '\t' || c == '\n' || c == '\r' -> index++
        c == '"' -> {
          next = when (next) {
            VALUE, VALUE_OR_CLOSE -> if (depth == 0) END else SEPARATOR_OR_CLOSE
            KEY, KEY_OR_CLOSE -> COLON
            else -> return false
          }
          index++
          var escaped = false
          while (index < text.length) {
            val s = text[index++]
            if (escaped) escaped = false
            else if (s == '\\') escaped = true
            else if (s == '"') break
          }
        }
        c == '{' || c == '[' -> {
          if ((next != VALUE && next != VALUE_OR_CLOSE) || depth == JSON_DEPTH) return false
          objects[depth++] = c == '{'
          next = if (c == '{') KEY_OR_CLOSE else VALUE_OR_CLOSE
          index++
        }
        c == '}' || c == ']' -> {
          val closesObject = c == '}'
          if (depth == 0 || objects[depth - 1] != closesObject) return false
          if (next != SEPARATOR_OR_CLOSE && next != (if (closesObject) KEY_OR_CLOSE else VALUE_OR_CLOSE)) {
            return false
          }
          depth--
          next = if (depth == 0) END else SEPARATOR_OR_CLOSE
          index++
        }
        c == ':' -> {
          if (next != COLON) return false
          next = VALUE
          index++
        }
        c == ',' -> {
          if (next != SEPARATOR_OR_CLOSE) return false
          next = if (objects[depth - 1]) KEY else VALUE
          index++
        }
        else -> {
          if (next != VALUE && next != VALUE_OR_CLOSE) return false
          val start = index
          while (index < text.length && !delimitsBareRun(text[index])) index++
          if (!bareToken(text, start, index)) return false
          next = if (depth == 0) END else SEPARATOR_OR_CLOSE
        }
      }
    }
    return true
  }

  // The token class [admitsJSON] accepts next.
  private const val VALUE = 0 // At the top level, after `:` and after `,` in an array.
  private const val VALUE_OR_CLOSE = 1 // After `[`.
  private const val KEY = 2 // After `,` in an object.
  private const val KEY_OR_CLOSE = 3 // After `{`.
  private const val COLON = 4 // After a key.
  private const val SEPARATOR_OR_CLOSE = 5 // After a value in a container.
  private const val END = 6 // After the top-level value: JSON whitespace only.

  private fun delimitsBareRun(c: Char): Boolean =
    c == '[' || c == ']' || c == '{' || c == '}' || c == ':' || c == ',' || c == '"' ||
      c == ' ' || c == '\t' || c == '\n' || c == '\r'

  /** Whether `text[start, end)` is exactly a JSON literal name or RFC 8259 §6 number. */
  private fun bareToken(text: String, start: Int, end: Int): Boolean {
    when (end - start) {
      4 -> if (text.startsWith("true", start) || text.startsWith("null", start)) return true
      5 -> if (text.startsWith("false", start)) return true
    }
    fun digitsFrom(from: Int): Int {
      var index = from
      while (index < end && text[index] in '0'..'9') index++
      return index
    }
    // -? (0 | [1-9][0-9]*) (\.[0-9]+)? ([eE][+-]?[0-9]+)?
    var index = if (text[start] == '-') start + 1 else start
    if (index < end && text[index] == '0') {
      index++
    } else {
      val integerEnd = digitsFrom(index)
      if (integerEnd == index) return false
      index = integerEnd
    }
    if (index < end && text[index] == '.') {
      val fractionEnd = digitsFrom(index + 1)
      if (fractionEnd == index + 1) return false
      index = fractionEnd
    }
    var hasExponent = false
    if (index < end && (text[index] == 'e' || text[index] == 'E')) {
      hasExponent = true
      var exponent = index + 1
      if (exponent < end && (text[exponent] == '+' || text[exponent] == '-')) exponent++
      val exponentEnd = digitsFrom(exponent)
      if (exponentEnd == exponent) return false
      index = exponentEnd
    }
    if (index != end) return false
    // Ordinary bounded integer/fraction spellings cannot overflow binary64.
    // Avoid allocating and parsing them twice on the common cell path.
    val tokenLength = end - start
    return (!hasExponent && tokenLength <= 308) || text.substring(start, end).toDoubleOrNull()?.isFinite() == true
  }

  // Eight units per JSON value, plus UTF-8 bytes in keys and string values.
  private fun styleContent(style: Any): Boolean {
    // Scalar JSON leaves cost eight units and need no captured traversal state.
    if (style !is String && style !is JSONArray && style !is JSONObject) return STYLE_BYTES >= 8
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

  private val metadataKeys = arrayOf("accessibilityTree", "accessibilityAnnouncements", "scrollRegions", "linkTargets")
  private val preferredDimensionKeys = arrayOf("preferredGridWidth", "preferredGridHeight")

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
        if (cell.length() != HostContract.CELL_ARITY) return false
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
        if (entry.length() != HostContract.DELTA_ROW_ARITY) return false
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
          if (size.length() != HostContract.SIZE_ARITY) return false
          val w = size.exactInt(0) ?: return false
          val h = size.exactInt(1) ?: return false
          if (w !in 0..RASTER_DIMENSION || h !in 0..RASTER_DIMENSION || w.toLong() * h > RASTER_PIXELS)
            return false
        }
      }
    }
    for (key in metadataKeys) {
      if ((record.optJSONArray(key)?.length() ?: 0) > METADATA_ENTRIES) return false
    }
    for (key in preferredDimensionKeys) {
      if (record.has(key) && (record.exactInt(key) ?: return false) !in 0..GRID_DIMENSION) return false
    }
    if (!grid(record.optInt("preferredGridWidth", 0), record.optInt("preferredGridHeight", 0))) return false
    return rangeRows(record.optJSONArray("links"), HostContract.LINK_RUN_ARITY, width, height) &&
      rangeRows(record.optJSONObject("damage")?.optJSONArray("textRows"), HostContract.TEXT_RANGE_ARITY, width, height)
  }

  private fun rangeRows(rows: JSONArray?, arity: Int, width: Int, height: Int): Boolean {
    if (rows == null || rows.length() == 0) return true
    if (rows.length() > height) return false
    val seen = mutableSetOf<Int>()
    for (index in 0 until rows.length()) {
      val entry = rows.optJSONArray(index) ?: return false
      if (entry.length() != HostContract.TEXT_ROW_ARITY) return false
      val y = entry.exactInt(0) ?: return false
      val ranges = entry.optJSONArray(1) ?: return false
      if (y !in 0 until height || !seen.add(y) || ranges.length() > width) return false
      var end = 0L
      for (rangeIndex in 0 until ranges.length()) {
        val range = ranges.optJSONArray(rangeIndex) ?: return false
        if (range.length() != arity) return false
        val start = range.exactInt(0)?.toLong() ?: return false
        val value = range.exactInt(1)?.toLong() ?: return false
        val stop = if (arity == HostContract.LINK_RUN_ARITY) start + value else value
        if (start < 0 || (arity == HostContract.LINK_RUN_ARITY && start < end) || stop < start || stop > width) return false
        end = stop
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
