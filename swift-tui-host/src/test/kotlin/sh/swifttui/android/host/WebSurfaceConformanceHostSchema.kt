package sh.swifttui.android.host

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Strict schemas for the two host-specific fixture languages and shared grids. */
internal object WebSurfaceConformanceHostSchema {
  fun validateAndroidABILabels(
    entry: WebSurfaceConformanceManifestEntry,
    steps: List<JSONObject>
  ) {
    val labels = mutableSetOf<String>()
    val pendingCopies = mutableListOf<Pair<String, Int>>()
    for ((index, step) in steps.withIndex()) {
      val context = "${entry.file}:${index + 2}"
      when {
        step.has("androidABI") -> {
          val command = step.getJSONObject("androidABI")
          when (command.getString("action")) {
            "sizeQuery" -> require(labels.add(command.getString("label"))) {
              "$context: duplicate size-query label"
            }
            "copy" -> {
              val label = command.getString("label")
              require(label in labels) {
                "$context: copy references a missing or forward label"
              }
              pendingCopies += label to command.getInt("capacity")
            }
          }
        }
        step.has("expect") -> {
          val deliveries = step.getJSONObject("expect").getJSONArray("androidDeliveries")
          require(deliveries.length() == pendingCopies.size) {
            "$context: one Android delivery is required per copy"
          }
          pendingCopies.forEachIndexed { deliveryIndex, (label, capacity) ->
            val delivery = deliveries.getJSONObject(deliveryIndex)
            require(
              delivery.getString("label") == label &&
                delivery.getInt("capacity") == capacity
            ) {
              "$context: delivery labels/capacities must match copies in order"
            }
          }
          pendingCopies.clear()
        }
      }
    }
    require(pendingCopies.isEmpty()) {
      "${entry.file}: Android copy observations remain unconsumed at EOF"
    }
  }

  fun validateChannelLifecycle(
    entry: WebSurfaceConformanceManifestEntry,
    steps: List<JSONObject>
  ) {
    var currentToken: Long? = 1
    var lastIssuedToken = 1L
    var phase = "active"
    var pendingSurfaceSends: Int? = null

    for ((index, step) in steps.withIndex()) {
      val context = "${entry.file}:${index + 2}"
      when {
        step.has("channel") -> {
          val channel = step.getJSONObject("channel")
          val action = channel.getString("action")
          if (action == "drainInput") continue
          val token = channel.getLong("token")
          require(token > 0 && token <= lastIssuedToken) {
            "$context: unknown/future connection token"
          }
          if (action == "closeClient" && token == currentToken) {
            currentToken = null
            phase = "detached"
          }
        }
        step.has("reconnect") -> {
          require(phase == "detached" && pendingSurfaceSends == null) {
            "$context: reconnect requires a detached session with no pending caps"
          }
          lastIssuedToken += 1
          currentToken = lastIssuedToken
          phase = "pre-capabilities"
          pendingSurfaceSends = step.getJSONObject("reconnect").getInt("capsAfter")
          if (pendingSurfaceSends == 0) {
            phase = "active"
            pendingSurfaceSends = null
          }
        }
        step.has("emit") && pendingSurfaceSends != null -> {
          val raw = step.getString("emit")
          if (raw.startsWith(SwiftTUIWebSurfaceSession.RECORD_PREFIX)) {
            pendingSurfaceSends = requireNotNull(pendingSurfaceSends) - 1
            if (pendingSurfaceSends == 0) {
              phase = "active"
              pendingSurfaceSends = null
            }
          }
        }
      }
    }
    require(pendingSurfaceSends == null) {
      "${entry.file}: unresolved delayed caps at EOF"
    }
    @Suppress("UNUSED_VARIABLE")
    val validatedCurrentToken = currentToken
  }

  fun validateAndroidABIStep(action: String, value: Any, context: String) {
    when (action) {
      "androidABI" -> {
        val command = value as? JSONObject ?: error("$context: androidABI must be an object")
        when (command.requiredString("action", "$context androidABI")) {
          "publish" -> {
            command.requireExactKeys(
              setOf("action", "sequence", "width", "height", "rows", "damage"),
              "$context publish"
            )
            command.requiredLong("sequence", context)
            val width = command.requiredInt("width", context)
            val height = command.requiredInt("height", context)
            validateGrid(command.requireArray("rows", context), "$context rows", width, height)
            if (!command.isNull("damage")) {
              validateDamage(command.getJSONObject("damage"), width, height, context)
            }
          }
          "sizeQuery" -> {
            command.requireExactKeys(setOf("action", "label"), "$context sizeQuery")
            require(command.requiredString("label", context).isNotEmpty())
          }
          "copy" -> {
            command.requireExactKeys(setOf("action", "label", "capacity"), "$context copy")
            require(command.requiredString("label", context).isNotEmpty())
            command.requiredInt("capacity", context)
          }
          else -> error("$context: unknown androidABI action")
        }
      }
      "expect" -> {
        val expect = value as? JSONObject ?: error("$context: expect must be an object")
        expect.requireExactKeys(setOf("androidDeliveries"), "$context expect")
        val deliveries = expect.requireArray("androidDeliveries", context)
        for (index in 0 until deliveries.length()) {
          val delivery = deliveries.requireObject(index, "$context androidDeliveries")
          delivery.requireExactKeys(
            setOf("label", "reported", "capacity", "returned", "copied", "record"),
            "$context android delivery"
          )
          require(delivery.requiredString("label", context).isNotEmpty())
          delivery.requiredInt("reported", context)
          delivery.requiredInt("capacity", context)
          delivery.requiredInt("returned", context)
          val copied = delivery.get("copied")
          require(copied is Boolean)
          if (copied) {
            val record = delivery.getJSONObject("record")
            record.requireExactKeys(
              setOf("kind", "epoch", "gen", "baselineGen", "rows"),
              "$context delivery record"
            )
            require(record.requiredString("kind", context) in setOf("full", "delta"))
            validateNullableInteger(record, "epoch", context)
            validateNullableInteger(record, "gen", context)
            validateNullableInteger(record, "baselineGen", context)
            validateGrid(record.requireArray("rows", context), "$context delivery rows")
          } else {
            require(delivery.isNull("record"))
          }
        }
      }
      else -> error("$context: android-abi accepts only androidABI and expect")
    }
  }

  fun validateChannelStep(
    entry: WebSurfaceConformanceManifestEntry,
    action: String,
    value: Any,
    context: String
  ) {
    require(entry.mutationClass == "websocket-detached-backlog")
    when (action) {
      "emit" -> {
        require(value is String)
        parseConformanceFramedRecord(value, context)
      }
      "reconnect" -> {
        val reconnect = value as? JSONObject ?: error("$context: reconnect must be an object")
        reconnect.requireExactKeys(setOf("capsAfter"), "$context reconnect")
        reconnect.requiredInt("capsAfter", context)
      }
      "channel" -> {
        val channel = value as? JSONObject ?: error("$context: channel must be an object")
        when (channel.requiredString("action", context)) {
          "closeClient" -> {
            channel.requireExactKeys(setOf("action", "token"), "$context closeClient")
            channel.requiredLong("token", context)
          }
          "clientChunk" -> {
            channel.requireExactKeys(
              setOf("action", "token", "bytesBase64"),
              "$context clientChunk"
            )
            channel.requiredLong("token", context)
            channel.requiredString("bytesBase64", context).requireCanonicalBase64(context)
          }
          "drainInput" -> channel.requireExactKeys(setOf("action"), "$context drainInput")
          else -> error("$context: unknown channel action")
        }
      }
      "expect" -> validateChannelExpectation(
        value as? JSONObject ?: error("$context: expect must be an object"),
        context
      )
      else -> error("$context: unknown websocket-channel action $action")
    }
  }

  fun validateGrid(
    rows: JSONArray,
    context: String,
    width: Int? = null,
    height: Int? = null
  ) {
    var previousRow = -1
    for (rowIndex in 0 until rows.length()) {
      val row = rows.requireObject(rowIndex, context)
      row.requireExactKeys(setOf("row", "cells"), "$context row")
      val y = row.requiredInt("row", context)
      require(y > previousRow) { "$context: rows must be strictly ascending" }
      if (height != null) require(y < height) { "$context: row is outside height" }
      previousRow = y
      var previousEnd = 0L
      var first = true
      val cells = row.requireArray("cells", context)
      for (cellIndex in 0 until cells.length()) {
        val cell = cells.requireObject(cellIndex, context)
        cell.requireExactKeys(setOf("column", "text", "span"), "$context cell")
        val column = cell.requiredInt("column", context)
        val span = cell.requiredInt("span", context, positive = true)
        cell.requiredString("text", context)
        val end = column.toLong() + span.toLong()
        require(end <= Int.MAX_VALUE.toLong()) {
          "$context: cell endpoint exceeds host Int"
        }
        require(first || column.toLong() >= previousEnd) {
          "$context: cells overlap or are unordered"
        }
        if (width != null) require(end <= width.toLong()) {
          "$context: cell exceeds frame width"
        }
        previousEnd = end
        first = false
      }
    }
  }

  private fun validateChannelExpectation(expect: JSONObject, context: String) {
    expect.requireExactKeys(
      setOf(
        "deliveredRecords",
        "suppressedSurfaceRecords",
        "detachedNonSurfaceBacklog",
        "refreshRequestCount",
        "capsProcessedCount",
        "ignoredStaleCallbackCount",
        "acceptedClientInputs",
        "discardedInboundChunks",
        "parser",
        "connection"
      ),
      "$context channel expect"
    )
    validateChannelRecords(
      expect.getJSONArray("deliveredRecords"),
      context,
      suppressed = false
    )
    validateChannelRecords(
      expect.getJSONArray("suppressedSurfaceRecords"),
      context,
      suppressed = true
    )
    val deliveredRaw = expect.getJSONArray("deliveredRecords").objects(context)
      .mapTo(mutableSetOf()) { it.getString("raw") }
    val suppressedRaw = expect.getJSONArray("suppressedSurfaceRecords").objects(context)
      .mapTo(mutableSetOf()) { it.getString("raw") }
    require(deliveredRaw.intersect(suppressedRaw).isEmpty()) {
      "$context: a record cannot be both delivered and suppressed"
    }
    expect.getJSONObject("detachedNonSurfaceBacklog").also {
      it.requireExactKeys(setOf("count", "bytes"), "$context backlog")
      it.requiredInt("count", context)
      it.requiredInt("bytes", context)
    }
    for (key in listOf("refreshRequestCount", "capsProcessedCount", "ignoredStaleCallbackCount")) {
      expect.requiredInt(key, context)
    }
    expect.getJSONArray("acceptedClientInputs").strings("$context acceptedClientInputs")
    val discarded = expect.getJSONArray("discardedInboundChunks")
    for (index in 0 until discarded.length()) {
      discarded.getJSONObject(index).also {
        it.requireExactKeys(setOf("token", "bytesBase64", "reason"), "$context discarded chunk")
        it.requiredLong("token", context)
        it.requiredString("bytesBase64", context).requireCanonicalBase64(context)
        require(
          it.requiredString("reason", context) in setOf(
            "stale-at-ingress",
            "stale-at-consumption",
            "connection-boundary",
            "terminal"
          )
        )
      }
    }
    expect.getJSONObject("parser").also {
      it.requireExactKeys(setOf("token", "bufferedBytes"), "$context parser")
      validateNullableInteger(it, "token", context)
      it.requiredInt("bufferedBytes", context)
    }
    expect.getJSONObject("connection").also {
      it.requireExactKeys(
        setOf("currentToken", "lastIssuedToken", "phase", "sceneInputFinished"),
        "$context connection"
      )
      validateNullableInteger(it, "currentToken", context)
      it.requiredLong("lastIssuedToken", context)
      require(it.requiredString("phase", context) in setOf("detached", "pre-capabilities", "active"))
      require(it.get("sceneInputFinished") is Boolean)
    }
  }

  private fun String.requireCanonicalBase64(context: String) {
    require(isNotEmpty()) { "$context: base64 bytes must be nonempty" }
    val decoded = runCatching { Base64.getDecoder().decode(this) }
      .getOrElse { throw IllegalArgumentException("$context: invalid base64", it) }
    require(Base64.getEncoder().encodeToString(decoded) == this) {
      "$context: bytesBase64 must use canonical padded base64"
    }
  }

  private fun JSONArray.objects(context: String): List<JSONObject> = buildList {
    for (index in 0 until this@objects.length()) {
      add(this@objects.requireObject(index, context))
    }
  }

  private fun validateChannelRecords(
    records: JSONArray,
    context: String,
    suppressed: Boolean
  ) {
    for (index in 0 until records.length()) {
      records.getJSONObject(index).also {
        it.requireExactKeys(
          setOf("raw", "kind", "epoch", "gen", "baselineGen"),
          "$context channel record"
        )
        val raw = it.requiredString("raw", context)
        val kind = it.requiredString("kind", context)
        require(kind in setOf("full", "delta", "non-surface"))
        val (recordKind, record) = parseConformanceFramedRecord(raw, "$context channel record")
        val derived = deriveChannelRecordMetadata(recordKind, record, context)
        require(
          kind == derived.kind &&
            nullableStepInteger(it, "epoch", context) == derived.epoch &&
            nullableStepInteger(it, "gen", context) == derived.gen &&
            nullableStepInteger(it, "baselineGen", context) == derived.baselineGen
        ) {
          "$context: channel metadata must match the raw record exactly"
        }
        require(!suppressed || derived.kind != "non-surface") {
          "$context: suppressedSurfaceRecords may contain only surface records"
        }
      }
    }
  }

  private data class ChannelRecordMetadata(
    val kind: String,
    val epoch: Long?,
    val gen: Long?,
    val baselineGen: Long?
  )

  private fun deriveChannelRecordMetadata(
    recordKind: String,
    record: JSONObject,
    context: String
  ): ChannelRecordMetadata {
    if (recordKind != "surface") {
      return ChannelRecordMetadata(
        kind = "non-surface",
        epoch = null,
        gen = null,
        baselineGen = null
      )
    }

    val encoding = if (record.has("encoding")) {
      record.requiredString("encoding", "$context raw surface")
    } else {
      null
    }
    require(encoding == null || encoding == "delta") {
      "$context: raw surface encoding must be absent or delta"
    }
    val epoch = optionalRawStamp(record, "epoch", context)
    val gen = optionalRawStamp(record, "gen", context)
    val baselineGen = optionalRawStamp(record, "baselineGen", context)
    val kind = if (encoding == "delta") "delta" else "full"
    if (kind == "full") {
      require(baselineGen == null && (epoch == null) == (gen == null)) {
        "$context: raw full-frame stamps are structurally invalid"
      }
    } else {
      require(
        (epoch == null && gen == null && baselineGen == null) ||
          (epoch != null && gen != null && baselineGen != null)
      ) {
        "$context: raw delta stamps are structurally invalid"
      }
    }
    return ChannelRecordMetadata(kind, epoch, gen, baselineGen)
  }

  private fun optionalRawStamp(
    record: JSONObject,
    name: String,
    context: String
  ): Long? {
    if (!record.has(name)) return null
    require(!record.isNull(name)) {
      "$context: raw surface $name must be absent or a nonnegative safe integer"
    }
    return stepInteger(record.get(name), "$context raw surface $name")
  }

  private fun validateDamage(damage: JSONObject, width: Int, height: Int, context: String) {
    damage.requireExactKeys(setOf("rows"), "$context damage")
    val rows = damage.getJSONArray("rows")
    var previousRow = -1
    for (index in 0 until rows.length()) {
      val row = rows.getJSONObject(index)
      row.requireExactKeys(setOf("row", "ranges"), "$context damage row")
      val y = row.requiredInt("row", context)
      require(y > previousRow && y < height)
      previousRow = y
      var previousEnd = -1L
      val ranges = row.getJSONArray("ranges")
      for (rangeIndex in 0 until ranges.length()) {
        val range = ranges.getJSONArray(rangeIndex)
        require(range.length() == 2)
        val start = stepInteger(range.get(0), "$context damage start")
        val end = stepInteger(range.get(1), "$context damage end")
        require(start <= Int.MAX_VALUE && end <= Int.MAX_VALUE) {
          "$context: damage endpoints exceed host Int"
        }
        require(start >= previousEnd && start < end && end <= width.toLong())
        previousEnd = end
      }
    }
  }
}
