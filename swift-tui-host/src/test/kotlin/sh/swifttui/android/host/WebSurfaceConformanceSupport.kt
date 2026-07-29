package sh.swifttui.android.host

import java.math.BigDecimal
import java.math.BigInteger
import java.net.JarURLConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal data class WebSurfaceConformanceManifestEntry(
  val file: String,
  val scenario: String,
  val kind: String,
  val mutationClass: String,
  val bodySHA256: String,
  val requiresStage: String,
  val runners: List<String>
)

internal data class WebSurfaceConformanceFixture(
  val entry: WebSurfaceConformanceManifestEntry,
  val steps: List<JSONObject>,
  val droppedEmitIndices: Set<Int>
)

internal data class WebSurfaceConformanceCorpus(
  val manifestSHA256: String,
  val entries: List<WebSurfaceConformanceManifestEntry>,
  val fixtures: Map<String, WebSurfaceConformanceFixture>
)

/**
 * Raw-byte loader for the shared host-wire conformance corpus.
 *
 * Parsing is deliberately downstream of byte validation and both hashes: a
 * malformed or stale mirror must fail before any scenario action can execute.
 */
internal object WebSurfaceConformanceLoader {
  const val MANIFEST_RESOURCE = "conformance-manifest.json"
  const val RUNNER_ID = "android"

  private val runnerOrder = listOf(
    "swift-reference",
    "web-canvas",
    "web-dom",
    "android",
    "swift-android-abi",
    "swift-websocket-channel"
  )
  private val stages = setOf("s1", "s2", "s3a", "s3b", "s3d")
  private val implementedStages = setOf("s1", "s2")
  private val kindRunners = mapOf(
    "record" to setOf("swift-reference", "web-canvas", "web-dom", "android"),
    "web-painter" to setOf("web-canvas", "web-dom"),
    "android-abi" to setOf("swift-android-abi"),
    "websocket-channel" to setOf("swift-websocket-channel")
  )
  private val mutationContract = mapOf(
    "control" to MutationContract("s1", setOf("record"), kindRunners.getValue("record")),
    "baseline-loss" to MutationContract("s1", setOf("record"), kindRunners.getValue("record")),
    "image-forget" to MutationContract(
      "s2",
      setOf("record", "web-painter"),
      setOf("swift-reference", "android", "web-canvas", "web-dom")
    ),
    "image-decode-failure" to MutationContract(
      "s2",
      setOf("web-painter"),
      setOf("web-canvas")
    ),
    "unknown-token" to MutationContract(
      "s1",
      setOf("record"),
      setOf("web-canvas", "web-dom", "android")
    ),
    "epoch-reanchor" to MutationContract(
      "s1",
      setOf("record"),
      kindRunners.getValue("record")
    ),
    "android-delivery-commit" to MutationContract(
      "s3a",
      setOf("android-abi"),
      setOf("swift-android-abi")
    ),
    "websocket-detached-backlog" to MutationContract(
      "s3b",
      setOf("websocket-channel"),
      setOf("swift-websocket-channel")
    ),
    "style-append" to MutationContract("s3d", setOf("record"), kindRunners.getValue("record"))
  )

  fun load(
    resourceBytes: (String) -> ByteArray = ::readResource,
    resourceCensus: () -> Set<String> = ::discoverConformanceResources
  ): WebSurfaceConformanceCorpus {
    val manifestBytes = resourceBytes(MANIFEST_RESOURCE)
    val manifestText = strictText(manifestBytes, MANIFEST_RESOURCE)
    val manifestHash = manifestBytes.sha256()
    val manifest = parseExactJSONObject(manifestText, MANIFEST_RESOURCE)
    manifest.requireExactKeys(setOf("fixtures", "formatVersion"), "manifest")
    require(manifest.requiredInt("formatVersion", "manifest") == 1) {
      "unsupported conformance manifest formatVersion"
    }
    val fixtureArray = manifest.requireArray("fixtures", "manifest")
    val entries = buildList {
      for (index in 0 until fixtureArray.length()) {
        add(parseEntry(fixtureArray.requireObject(index, "manifest fixtures")))
      }
    }
    require(entries.map { it.file } == entries.map { it.file }.sorted()) {
      "manifest fixtures must be sorted by filename"
    }
    require(entries.map { it.file }.toSet().size == entries.size) {
      "manifest fixture filenames must be unique"
    }
    require(entries.map { it.scenario }.toSet().size == entries.size) {
      "manifest scenarios must be unique"
    }
    require(entries.none { it.requiresStage == "s3d" }) {
      "S5 must not contain an s3d fixture"
    }

    val declaredFiles = entries.mapTo(linkedSetOf()) { it.file }
    val localFiles = resourceCensus()
    require(localFiles == declaredFiles) {
      "conformance resource census mismatch: declared=$declaredFiles local=$localFiles"
    }

    val fixtures = linkedMapOf<String, WebSurfaceConformanceFixture>()
    for (entry in entries) {
      val fixtureBytes = resourceBytes(entry.file)
      val fixtureText = strictText(fixtureBytes, entry.file)
      val firstLineEnd = fixtureBytes.indexOf(0x0A)
      require(firstLineEnd >= 0) { "${entry.file}: missing fixture header LF" }
      val headerBytes = fixtureBytes.copyOfRange(0, firstLineEnd)
      val bodyBytes = fixtureBytes.copyOfRange(firstLineEnd + 1, fixtureBytes.size)
      val header = parseExactJSONObject(
        strictUTF8(headerBytes, "${entry.file} header"),
        "${entry.file} header"
      )
      header.requireExactKeys(
        setOf("formatVersion", "manifestSHA256", "bodySHA256"),
        "${entry.file} header"
      )
      require(header.requiredInt("formatVersion", "${entry.file} header") == 1)
      require(header.requiredString("manifestSHA256", "${entry.file} header") == manifestHash) {
        "${entry.file}: manifest hash mismatch"
      }
      val bodyHash = bodyBytes.sha256()
      require(header.requiredString("bodySHA256", "${entry.file} header") == bodyHash) {
        "${entry.file}: header body hash mismatch"
      }
      require(entry.bodySHA256 == bodyHash) {
        "${entry.file}: manifest body hash mismatch"
      }

      val lines = fixtureText.split('\n').dropLast(1)
      require(lines.size >= 2) { "${entry.file}: fixture body must contain steps" }
      val steps = lines.drop(1).mapIndexed { index, line ->
        require(line.isNotEmpty()) { "${entry.file}:${index + 2}: blank lines are forbidden" }
        parseExactJSONObject(line, "${entry.file}:${index + 2}").also {
          validateStep(entry, it, "${entry.file}:${index + 2}")
        }
      }
      val droppedEmitIndices = resolveDrops(steps, entry.file)
      validateScenarioSemantics(entry, steps, droppedEmitIndices)
      fixtures[entry.file] = WebSurfaceConformanceFixture(
        entry = entry,
        steps = steps,
        droppedEmitIndices = droppedEmitIndices
      )
    }

    return WebSurfaceConformanceCorpus(
      manifestSHA256 = manifestHash,
      entries = entries,
      fixtures = fixtures
    )
  }

  fun activeAndroidFixtures(corpus: WebSurfaceConformanceCorpus): List<WebSurfaceConformanceFixture> =
    corpus.entries.filter {
      RUNNER_ID in it.runners && it.requiresStage in implementedStages
    }.map { corpus.fixtures.getValue(it.file) }

  fun assertJSONObjectEquals(expected: JSONObject, actual: JSONObject) {
    require(canonicalJSON(expected) == canonicalJSON(actual)) {
      "conformance observation mismatch\nexpected=${canonicalJSON(expected)}" +
        "\nactual=${canonicalJSON(actual)}"
    }
  }

  fun canonicalJSON(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted()
      .joinToString(",", prefix = "{", postfix = "}") { key ->
        "${JSONObject.quote(key)}:${canonicalJSON(value.get(key))}"
      }
    is JSONArray -> (0 until value.length()).joinToString(",", prefix = "[", postfix = "]") {
      canonicalJSON(value.get(it))
    }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> error("unsupported JSON value ${value::class.java.name}")
  }

  private fun parseEntry(objectValue: JSONObject): WebSurfaceConformanceManifestEntry {
    val context = "manifest fixture"
    objectValue.requireExactKeys(
      setOf(
        "file",
        "scenario",
        "kind",
        "mutationClass",
        "bodySHA256",
        "requiresStage",
        "runners"
      ),
      context
    )
    val file = objectValue.requiredString("file", context)
    val scenario = objectValue.requiredString("scenario", context)
    val kind = objectValue.requiredString("kind", context)
    val mutationClass = objectValue.requiredString("mutationClass", context)
    val bodyHash = objectValue.requiredString("bodySHA256", context)
    val stage = objectValue.requiredString("requiresStage", context)
    val runners = objectValue.requireArray("runners", context).strings("$context runners")

    require(file.matches(Regex("""conformance-[a-z0-9-]+\.jsonl"""))) {
      "invalid conformance fixture filename $file"
    }
    require(scenario.matches(Regex("""[a-z0-9]+(?:-[a-z0-9]+)*"""))) {
      "invalid conformance scenario $scenario"
    }
    require(bodyHash.matches(Regex("[0-9a-f]{64}"))) { "$file: invalid body SHA-256" }
    require(stage in stages) { "$file: unknown stage $stage" }
    val legalKindRunners = requireNotNull(kindRunners[kind]) { "$file: unknown kind $kind" }
    require(runners.isNotEmpty() && runners.distinct() == runners) {
      "$file: runner list must be nonempty and unique"
    }
    require(runners == runners.sortedBy(runnerOrder::indexOf)) {
      "$file: runner list has noncanonical order"
    }
    require(runners.all { it in runnerOrder && it in legalKindRunners }) {
      "$file: illegal runner for kind $kind"
    }
    val mutation = requireNotNull(mutationContract[mutationClass]) {
      "$file: unknown mutation class $mutationClass"
    }
    require(stage == mutation.stage && kind in mutation.kinds) {
      "$file: mutation/stage/kind contract mismatch"
    }
    require(runners.all { it in mutation.runners }) {
      "$file: mutation runner applicability mismatch"
    }
    if (mutationClass != "image-forget") {
      require(runners == mutation.runners.filterTo(linkedSetOf()) { it in legalKindRunners }.toList()
        .sortedBy(runnerOrder::indexOf)) {
        "$file: incomplete mutation runner census"
      }
    } else {
      val expected = if (kind == "record") {
        listOf("swift-reference", "android")
      } else {
        listOf("web-canvas", "web-dom")
      }
      require(runners == expected) { "$file: image-forget runner census mismatch" }
    }
    return WebSurfaceConformanceManifestEntry(
      file,
      scenario,
      kind,
      mutationClass,
      bodyHash,
      stage,
      runners
    )
  }

  private fun validateStep(
    entry: WebSurfaceConformanceManifestEntry,
    step: JSONObject,
    context: String
  ) {
    require(step.length() == 1) { "$context: each line must contain exactly one action" }
    val action = step.keys().next()
    when (entry.kind) {
      "record", "web-painter" -> validateRecordStep(entry, action, step.get(action), context)
      "android-abi" -> WebSurfaceConformanceHostSchema.validateAndroidABIStep(
        action,
        step.get(action),
        context
      )
      "websocket-channel" -> WebSurfaceConformanceHostSchema.validateChannelStep(
        entry,
        action,
        step.get(action),
        context
      )
      else -> error("$context: unsupported kind ${entry.kind}")
    }
  }

  private fun validateScenarioSemantics(
    entry: WebSurfaceConformanceManifestEntry,
    steps: List<JSONObject>,
    droppedEmitIndices: Set<Int>
  ) {
    when (entry.kind) {
      "record" -> Unit
      "web-painter" -> validateDecodePlans(entry, steps, droppedEmitIndices)
      "android-abi" -> WebSurfaceConformanceHostSchema.validateAndroidABILabels(entry, steps)
      "websocket-channel" ->
        WebSurfaceConformanceHostSchema.validateChannelLifecycle(entry, steps)
    }
  }

  private fun validateDecodePlans(
    entry: WebSurfaceConformanceManifestEntry,
    steps: List<JSONObject>,
    droppedEmitIndices: Set<Int>
  ) {
    data class Plan(var remaining: Int)
    val plans = mutableMapOf<String, Plan>()
    for ((index, step) in steps.withIndex()) {
      val context = "${entry.file}:${index + 2}"
      when {
        step.has("decodeFailure") -> {
          val plan = step.getJSONObject("decodeFailure")
          val id = plan.getString("id")
          require(id !in plans) { "$context: duplicate active decode plan for $id" }
          plans[id] = Plan(plan.getJSONArray("outcomes").length())
        }
        step.has("emit") && index !in droppedEmitIndices -> {
          val record = parseConformanceSurfaceRecord(step.getString("emit"), context)
          val images = record.optJSONArray("images") ?: JSONArray()
          for (imageIndex in 0 until images.length()) {
            val id = images.requireObject(imageIndex, "$context images")
              .requiredString("id", "$context image")
            val plan = plans[id] ?: continue
            require(plan.remaining > 0) { "$context: decode plan for $id is exhausted" }
            plan.remaining -= 1
          }
        }
        step.has("expect") -> {
          require(plans.all { it.value.remaining == 0 }) {
            "$context: decode outcomes remain unconsumed"
          }
          plans.clear()
        }
      }
    }
    require(plans.all { it.value.remaining == 0 }) {
      "${entry.file}: decode outcomes remain unconsumed at EOF"
    }
  }

  private fun validateRecordStep(
    entry: WebSurfaceConformanceManifestEntry,
    action: String,
    value: Any,
    context: String
  ) {
    when (action) {
      "emit" -> {
        require(value is String && value.startsWith(SwiftTUIWebSurfaceSession.RECORD_PREFIX)) {
          "$context: emit must contain an RS-framed surface record"
        }
        require(value.endsWith("\n") && !value.dropLast(1).endsWith("\n")) {
          "$context: emitted record must have exactly one terminal LF"
        }
        parseConformanceSurfaceRecord(value, context)
      }
      "drop" -> require(stepInteger(value, "$context drop", positive = true) <= Int.MAX_VALUE)
      "evictImages" -> {
        require(entry.mutationClass == "image-forget") {
          "$context: evictImages requires image-forget"
        }
        (value as? JSONArray ?: error("$context: evictImages must be an array"))
          .strings("$context evictImages")
      }
      "reconnect" -> {
        require(value is JSONObject && value.length() == 0) {
          "$context: reconnect must be an empty object"
        }
      }
      "decodeFailure" -> {
        require(
          entry.kind == "web-painter" &&
            entry.mutationClass == "image-decode-failure" &&
            entry.runners == listOf("web-canvas")
        ) { "$context: decodeFailure is canvas image-decode-failure only" }
        val plan = value as? JSONObject ?: error("$context: decodeFailure must be an object")
        plan.requireExactKeys(setOf("id", "outcomes"), "$context decodeFailure")
        require(plan.requiredString("id", "$context decodeFailure").isNotEmpty())
        val outcomes = plan.requireArray("outcomes", context).strings("$context outcomes")
        require(outcomes.isNotEmpty() && outcomes.all { it == "failure" || it == "success" })
      }
      "expect" -> validateRecordExpectation(
        value as? JSONObject ?: error("$context: expect must be an object"),
        entry.kind,
        context
      )
      else -> error("$context: unknown or kind-inapplicable action $action")
    }
  }

  private fun validateRecordExpectation(expect: JSONObject, kind: String, context: String) {
    val required = setOf("rows", "imagesVisible", "resyncRequests")
    val allowed = if (kind == "record") required + "styleRuns" else required
    expect.requireAllowedAndRequiredKeys(allowed, required, "$context expect")
    WebSurfaceConformanceHostSchema.validateGrid(
      expect.requireArray("rows", context),
      "$context rows"
    )
    val images = expect.requireArray("imagesVisible", context).strings("$context imagesVisible")
    require(images == images.sorted() && images.distinct() == images) {
      "$context: imagesVisible must be a sorted set"
    }
    validateResyncRequests(expect.requireArray("resyncRequests", context), context)
    if (expect.has("styleRuns")) {
      validateStyleRuns(expect.getJSONArray("styleRuns"), expect.getJSONArray("rows"), context)
    }
  }

  private fun validateResyncRequests(array: JSONArray, context: String) {
    for (index in 0 until array.length()) {
      val request = array.requireObject(index, "$context resyncRequests")
      when (request.requiredString("scope", "$context resync request")) {
        "keyframe" -> request.requireExactKeys(setOf("scope"), "$context keyframe request")
        "images" -> {
          request.requireExactKeys(setOf("scope", "ids"), "$context image request")
          val ids = request.requireArray("ids", context).strings("$context image ids")
          require(ids == ids.sorted() && ids.distinct() == ids) {
            "$context: image request IDs must be a sorted set"
          }
        }
        else -> error("$context: unknown resync request scope")
      }
    }
  }

  internal fun validateStyleRuns(runs: JSONArray, rows: JSONArray, context: String) {
    data class GridCell(val column: Int, val span: Int, val text: String)
    val gridRows = mutableMapOf<Int, List<GridCell>>()
    for (rowIndex in 0 until rows.length()) {
      val row = rows.requireObject(rowIndex, "$context rows")
      val y = row.requiredInt("row", context)
      val cellsJSON = row.requireArray("cells", context)
      gridRows[y] = buildList {
        for (cellIndex in 0 until cellsJSON.length()) {
          val cell = cellsJSON.requireObject(cellIndex, "$context cells")
          add(
            GridCell(
              column = cell.requiredInt("column", context),
              span = cell.requiredInt("span", context, positive = true),
              text = cell.requiredString("text", context)
            )
          )
        }
      }
    }
    var previousRow = -1
    var previousEnd = -1L
    var previousStyle: String? = null
    val coveredCells = mutableSetOf<Pair<Int, Int>>()
    for (index in 0 until runs.length()) {
      val run = runs.requireObject(index, "$context styleRuns")
      run.requireExactKeys(
        setOf("row", "startColumn", "text", "span", "resolvedStyle"),
        "$context style run"
      )
      val row = run.requiredInt("row", context)
      val column = run.requiredInt("startColumn", context)
      val span = run.requiredInt("span", context, positive = true)
      val end = column.toLong() + span.toLong()
      require(end <= Int.MAX_VALUE.toLong()) {
        "$context: style run endpoint exceeds host Int"
      }
      require(row > previousRow || (row == previousRow && column.toLong() >= previousEnd)) {
        "$context: style runs must be ordered and nonoverlapping"
      }
      val resolvedStyle = run.get("resolvedStyle")
      require(resolvedStyle is JSONObject) {
        "$context: resolvedStyle must be an object"
      }
      val canonicalStyle = canonicalJSON(resolvedStyle)
      if (row == previousRow && column.toLong() == previousEnd) {
        require(canonicalStyle != previousStyle) {
          "$context: adjacent equal resolved styles must be coalesced"
        }
      }

      val cells = gridRows[row].orEmpty()
      val startIndex = cells.indexOfFirst { it.column == column }
      require(startIndex >= 0) { "$context: style run must begin on a grid-cell boundary" }
      var cursor = column.toLong()
      var text = ""
      var cellIndex = startIndex
      while (cursor < end) {
        val cell = cells.getOrNull(cellIndex)
          ?: throw IllegalArgumentException("$context: style run exceeds its grid row")
        require(cell.column.toLong() == cursor) {
          "$context: style run may not cross a grid gap"
        }
        val cellEnd = cell.column.toLong() + cell.span.toLong()
        require(cellEnd <= end) {
          "$context: style run may not split a grid cell"
        }
        require(coveredCells.add(row to cell.column)) {
          "$context: styled grid cells may not be covered twice"
        }
        text += cell.text
        cursor = cellEnd
        cellIndex += 1
      }
      require(cursor == end) { "$context: style run span must exactly cover grid cells" }
      require(run.requiredString("text", context) == text) {
        "$context: style run text must exactly concatenate covered cells"
      }
      previousRow = row
      previousEnd = end
      previousStyle = canonicalStyle
    }
  }

  private fun resolveDrops(steps: List<JSONObject>, file: String): Set<Int> {
    val claimed = mutableSetOf<Int>()
    for (dropIndex in steps.indices) {
      val step = steps[dropIndex]
      if (!step.has("drop")) continue
      var remaining = stepHostInt(step.get("drop"), "$file drop", positive = true)
      var cursor = dropIndex - 1
      while (cursor >= 0 && remaining > 0) {
        val candidate = steps[cursor]
        when {
          candidate.has("drop") -> Unit
          candidate.has("emit") -> if (claimed.add(cursor)) remaining -= 1
          else -> throw IllegalArgumentException(
            "$file: drop cannot cross action at step ${cursor + 1}"
          )
        }
        cursor -= 1
      }
      require(remaining == 0) { "$file: drop lacks enough preceding unmatched emits" }
    }
    return claimed
  }

  private fun strictText(bytes: ByteArray, name: String): String {
    require(bytes.isNotEmpty()) { "$name: file is empty" }
    require(!(bytes.size >= 3 &&
      bytes[0] == 0xEF.toByte() &&
      bytes[1] == 0xBB.toByte() &&
      bytes[2] == 0xBF.toByte())) {
      "$name: UTF-8 BOM is forbidden"
    }
    require(bytes.none { it == 0x0D.toByte() }) { "$name: CR bytes are forbidden" }
    require(bytes.none { it == 0x00.toByte() }) { "$name: NUL bytes are forbidden" }
    require(bytes.last() == 0x0A.toByte()) { "$name: exactly one terminal LF is required" }
    require(bytes.size == 1 || bytes[bytes.lastIndex - 1] != 0x0A.toByte()) {
      "$name: exactly one terminal LF is required"
    }
    val text = strictUTF8(bytes, name)
    require(!text.contains("\n\n")) { "$name: blank lines are forbidden" }
    return text
  }

  private fun strictUTF8(bytes: ByteArray, name: String): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
      .getOrElse { throw IllegalArgumentException("$name: invalid UTF-8", it) }
  }

  private fun readResource(name: String): ByteArray =
    requireNotNull(
      requireNotNull(WebSurfaceConformanceLoader::class.java.classLoader)
        .getResourceAsStream(name)
    ) {
      "missing conformance resource $name"
    }.use { it.readBytes() }

  private fun discoverConformanceResources(): Set<String> {
    val classLoader = requireNotNull(WebSurfaceConformanceLoader::class.java.classLoader)
    val manifestURL = requireNotNull(
      classLoader.getResource(MANIFEST_RESOURCE)
    ) { "missing $MANIFEST_RESOURCE" }
    return when (manifestURL.protocol) {
      "file" -> requireNotNull(java.io.File(manifestURL.toURI()).parentFile)
        .listFiles()
        .orEmpty()
        .map { it.name }
        .filterTo(sortedSetOf()) { it.matches(Regex("""conformance-.*\.jsonl""")) }
      "jar" -> {
        val connection = manifestURL.openConnection() as JarURLConnection
        connection.jarFile.use { jar ->
          jar.entries().asSequence()
            .map { it.name.substringAfterLast('/') }
            .filterTo(sortedSetOf()) { it.matches(Regex("""conformance-.*\.jsonl""")) }
        }
      }
      else -> error("unsupported test-resource protocol ${manifestURL.protocol}")
    }
  }

  private data class MutationContract(
    val stage: String,
    val kinds: Set<String>,
    val runners: Set<String>
  )
}

private fun ByteArray.sha256(): String =
  MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

internal fun parseConformanceSurfaceRecord(raw: String, context: String): JSONObject {
  val (kind, objectValue) = parseConformanceFramedRecord(raw, context)
  require(kind == "surface") { "$context: not a surface record" }
  return objectValue
}

internal fun parseConformanceFramedRecord(raw: String, context: String): Pair<String, JSONObject> {
  require(raw.isNotEmpty() && raw.first() == '\u001E') {
    "$context: emitted record must begin with exactly one RS"
  }
  require(raw.indexOf('\u001E', startIndex = 1) < 0) {
    "$context: emitted record contains a second RS prefix"
  }
  require(raw.endsWith("\n") && !raw.dropLast(1).endsWith("\n")) {
    "$context: emitted record must have exactly one terminal LF and no trailing bytes"
  }
  val withoutLF = raw.dropLast(1)
  val colon = withoutLF.indexOf(':')
  require(colon > 1) { "$context: emitted record is missing its kind separator" }
  val kind = withoutLF.substring(1, colon)
  require(kind.matches(Regex("[A-Za-z][A-Za-z0-9]*"))) {
    "$context: invalid emitted record kind"
  }
  val json = withoutLF.substring(colon + 1)
  require(json.startsWith("{") && json.endsWith("}")) {
    "$context: emitted record must contain exactly one JSON object before its LF"
  }
  return kind to parseExactJSONObject(json, "$context emitted record")
}

internal fun parseExactJSONObject(text: String, context: String): JSONObject {
  require('\u0000' !in text) { "$context: NUL bytes are forbidden" }
  val tokener = JSONTokener(text)
  val value = runCatching { tokener.nextValue() }
    .getOrElse { throw IllegalArgumentException("$context: invalid JSON", it) }
  require(value is JSONObject) { "$context: expected one JSON object" }
  while (tokener.more()) {
    require(tokener.next() <= ' ') {
      "$context: trailing JSON bytes or a second value are forbidden"
    }
  }
  require(tokener.next() == '\u0000') { "$context: JSON parser did not reach EOF" }
  return value
}

internal fun JSONObject.requireExactKeys(expected: Set<String>, context: String) {
  val actual = keys().asSequence().toSet()
  require(actual == expected) { "$context: expected keys $expected, found $actual" }
}

internal fun JSONObject.requireAllowedAndRequiredKeys(
  allowed: Set<String>,
  required: Set<String>,
  context: String
) {
  val actual = keys().asSequence().toSet()
  require(actual.all { it in allowed } && actual.containsAll(required)) {
    "$context: invalid keys $actual"
  }
}

internal fun JSONObject.requiredString(name: String, context: String): String {
  require(has(name) && !isNull(name) && get(name) is String) {
    "$context: $name must be a string"
  }
  return getString(name)
}

internal fun JSONObject.requiredLong(name: String, context: String): Long =
  stepInteger(get(name), "$context $name")

internal fun JSONObject.requiredInt(
  name: String,
  context: String,
  positive: Boolean = false
): Int = stepHostInt(get(name), "$context $name", positive)

internal fun stepHostInt(value: Any, context: String, positive: Boolean = false): Int {
  val parsed = stepInteger(value, context, positive)
  require(parsed <= Int.MAX_VALUE) { "$context exceeds host Int" }
  return parsed.toInt()
}

internal fun JSONObject.requireArray(name: String, context: String): JSONArray {
  require(has(name) && get(name) is JSONArray) { "$context: $name must be an array" }
  return getJSONArray(name)
}

internal fun JSONArray.requireObject(index: Int, context: String): JSONObject {
  require(get(index) is JSONObject) { "$context[$index] must be an object" }
  return getJSONObject(index)
}

internal fun JSONArray.strings(context: String): List<String> = buildList {
  for (index in 0 until this@strings.length()) {
    require(this@strings.get(index) is String) { "$context[$index] must be a string" }
    add(this@strings.getString(index))
  }
}

internal fun validateNullableInteger(objectValue: JSONObject, name: String, context: String) {
  nullableStepInteger(objectValue, name, context)
}

internal fun nullableStepInteger(
  objectValue: JSONObject,
  name: String,
  context: String
): Long? {
  require(objectValue.has(name)) { "$context: missing $name" }
  return if (objectValue.isNull(name)) {
    null
  } else {
    stepInteger(objectValue.get(name), "$context $name")
  }
}

internal fun stepInteger(value: Any, context: String, positive: Boolean = false): Long {
  require(value is Number) { "$context must be an integer" }
  val integer = runCatching {
    when (value) {
      is BigInteger -> value
      is BigDecimal -> {
        require(value.signum() >= 0 && value <= MAX_SAFE_DECIMAL)
        value.toBigIntegerExact()
      }
      is Byte, is Short, is Int, is Long -> BigInteger.valueOf(value.toLong())
      is Float -> {
        require(value.isFinite())
        BigDecimal.valueOf(value.toDouble()).toBigIntegerExact()
      }
      is Double -> {
        require(value.isFinite())
        BigDecimal.valueOf(value).toBigIntegerExact()
      }
      else -> throw IllegalArgumentException(
        "$context has unsupported number type ${value::class.java.name}"
      )
    }
  }.getOrElse {
    throw IllegalArgumentException("$context must be a nonnegative safe integer", it)
  }
  require(integer >= BigInteger.ZERO && integer <= MAX_SAFE_INTEGER) {
    "$context must be a nonnegative safe integer"
  }
  val result = integer.toLong()
  require(!positive || result > 0) { "$context must be positive" }
  return result
}

private val MAX_SAFE_INTEGER = BigInteger("9007199254740991")
private val MAX_SAFE_DECIMAL = BigDecimal(MAX_SAFE_INTEGER)
