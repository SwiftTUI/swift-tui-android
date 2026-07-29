package sh.swifttui.android.host

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSurfaceConformanceTest {
  @Test
  fun fullMirrorPassesRawByteHashSchemaApplicabilityAndCensusValidation() {
    val corpus = WebSurfaceConformanceLoader.load()

    assertEquals(9, corpus.entries.size)
    assertEquals(
      setOf(
        "android-delivery-commits-copied-candidate",
        "baseline-loss-refuses-stale-delta",
        "control-steady-delta",
        "epoch-reanchor-and-style-budget-full",
        "canvas-decode-failure-retries-deterministically",
        "image-forget-requests-and-reapplies",
        "web-painter-image-forget-requests-and-reapplies",
        "unknown-token-degrades-per-record",
        "websocket-detached-backlog-reconnects-by-token"
      ),
      corpus.entries.mapTo(linkedSetOf()) { it.scenario }
    )
    assertFalse(corpus.entries.any { it.requiresStage == "s3d" })
    assertEquals(
      setOf("s3a", "s3b"),
      corpus.entries
        .filter { it.requiresStage !in setOf("s1", "s2") }
        .mapTo(linkedSetOf()) { it.requiresStage }
    )
  }

  @Test
  fun androidRunsEveryDeclaredS1AndS2ScenarioAndNoOthers() {
    val corpus = WebSurfaceConformanceLoader.load()
    val active = WebSurfaceConformanceLoader.activeAndroidFixtures(corpus)

    assertEquals(
      listOf(
        "baseline-loss-refuses-stale-delta",
        "control-steady-delta",
        "epoch-reanchor-and-style-budget-full",
        "image-forget-requests-and-reapplies",
        "unknown-token-degrades-per-record"
      ),
      active.map { it.entry.scenario }
    )
    active.forEach { WebSurfaceConformanceRunner().run(it) }
  }

  @Test
  fun observationComparatorHasTeethOnEveryAndroidAxis() {
    val baseline = parseExactJSONObject(
      """
      {
        "rows":[
          {"row":0,"cells":[
            {"column":0,"text":"A","span":1},
            {"column":2,"text":"界","span":2}
          ]},
          {"row":2,"cells":[{"column":1,"text":"Z","span":1}]}
        ],
        "imagesVisible":["alpha","beta"],
        "resyncRequests":[
          {"scope":"keyframe"},
          {"scope":"images","ids":["alpha","beta"]}
        ],
        "styleRuns":[
          {
            "row":0,
            "startColumn":0,
            "text":"A",
            "span":1,
            "resolvedStyle":{"fg":"#E05757FF"}
          },
          {
            "row":0,
            "startColumn":2,
            "text":"界",
            "span":2,
            "resolvedStyle":{"fg":"#5BA3FFFF"}
          }
        ]
      }
      """.trimIndent(),
      "meta-test baseline"
    )
    val mutations = linkedMapOf<String, (JSONObject) -> Unit>(
      "grid row count" to { it.getJSONArray("rows").remove(1) },
      "grid cell count" to {
        it.getJSONArray("rows").getJSONObject(0).getJSONArray("cells").remove(1)
      },
      "grid row coordinate" to {
        it.getJSONArray("rows").getJSONObject(1).put("row", 3)
      },
      "grid column coordinate" to {
        it.getJSONArray("rows").getJSONObject(0).getJSONArray("cells")
          .getJSONObject(0).put("column", 1)
      },
      "grid text" to {
        it.getJSONArray("rows").getJSONObject(0).getJSONArray("cells")
          .getJSONObject(0).put("text", "B")
      },
      "grid span" to {
        it.getJSONArray("rows").getJSONObject(0).getJSONArray("cells")
          .getJSONObject(1).put("span", 1)
      },
      "intentional gap width" to {
        it.getJSONArray("rows").getJSONObject(0).getJSONArray("cells")
          .getJSONObject(1).put("column", 3)
      },
      "intentional gap presence" to {
        it.getJSONArray("rows").getJSONObject(0).getJSONArray("cells")
          .getJSONObject(1).put("column", 1)
      },
      "visible image ID" to {
        it.getJSONArray("imagesVisible").put(0, "gamma")
      },
      "visible image count" to {
        it.getJSONArray("imagesVisible").remove(1)
      },
      "resync scope" to {
        it.getJSONArray("resyncRequests").getJSONObject(0).put("scope", "images")
      },
      "resync IDs" to {
        it.getJSONArray("resyncRequests").getJSONObject(1).getJSONArray("ids")
          .put(0, "gamma")
      },
      "resync ordering" to {
        val requests = it.getJSONArray("resyncRequests")
        val first = requests.get(0)
        requests.put(0, requests.get(1))
        requests.put(1, first)
      },
      "resync ID ordering" to {
        val ids = it.getJSONArray("resyncRequests").getJSONObject(1).getJSONArray("ids")
        val first = ids.get(0)
        ids.put(0, ids.get(1))
        ids.put(1, first)
      },
      "resync count" to {
        it.getJSONArray("resyncRequests").remove(1)
      },
      "style position" to {
        it.getJSONArray("styleRuns").getJSONObject(0).put("startColumn", 1)
      },
      "style span" to {
        it.getJSONArray("styleRuns").getJSONObject(1).put("span", 1)
      },
      "style text" to {
        it.getJSONArray("styleRuns").getJSONObject(0).put("text", "B")
      },
      "style gap boundary" to {
        it.getJSONArray("styleRuns").getJSONObject(1).put("startColumn", 1)
      },
      "resolved style" to {
        it.getJSONArray("styleRuns").getJSONObject(0)
          .getJSONObject("resolvedStyle").put("fg", "#000000FF")
      }
    )

    for ((axis, mutate) in mutations) {
      val corrupted = parseExactJSONObject(baseline.toString(), "meta-test clone")
      mutate(corrupted)
      assertThrows(axis, IllegalArgumentException::class.java) {
        WebSurfaceConformanceLoader.assertJSONObjectEquals(corrupted, baseline)
      }
    }
  }

  @Test
  fun exactJSONAndEmittedRecordParsingRejectTrailingOrRepeatedValuesAndFrames() {
    val malformedJSON = listOf(
      """{} trailing""",
      """{}{}""",
      """{"first":true} {"second":true}""",
      "{}\u0000{}"
    )
    malformedJSON.forEach { json ->
      assertThrows(IllegalArgumentException::class.java) {
        parseExactJSONObject(json, "negative JSON")
      }
    }

    val prefix = SwiftTUIWebSurfaceSession.RECORD_PREFIX
    val malformedRecords = listOf(
      prefix + """{"version":2} trailing""" + "\n",
      prefix + """{"version":2}{}""" + "\n",
      prefix + """{"version":2}""" + "\n" + prefix + """{"version":2}""" + "\n",
      prefix + """{"version":2}""" + "\n" + "trailing",
      prefix + """{"version":2}""" + "\n\n",
      prefix + prefix + """{"version":2}""" + "\n",
      prefix + """{"version":2}""" + "\u0000{}\n"
    )
    malformedRecords.forEach { record ->
      assertThrows(IllegalArgumentException::class.java) {
        parseConformanceSurfaceRecord(record, "negative surface record")
      }
    }
  }

  @Test
  fun channelRecordSchemaDerivesEveryMetadataFieldAndRejectsSuppressedNonSurface() {
    val entry = WebSurfaceConformanceManifestEntry(
      file = "conformance-websocket-detached-backlog.jsonl",
      scenario = "websocket-detached-backlog-reconnects-by-token",
      kind = "websocket-channel",
      mutationClass = "websocket-detached-backlog",
      bodySHA256 = "0".repeat(64),
      requiresStage = "s3b",
      runners = listOf("swift-websocket-channel")
    )
    val fullRaw =
      "\u001Esurface:" +
        """{"version":2,"epoch":7,"gen":1,"width":1,"height":1,"styles":[null],"rows":[],"images":[]}""" +
        "\n"
    val deltaRaw =
      "\u001Esurface:" +
        """{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":1,"height":1,"styles":[null],"deltaRows":[],"images":[]}""" +
        "\n"
    val nonSurfaceRaw = "\u001EruntimeIssue:{\"message\":\"detached\"}\n"

    fun record(
      raw: String,
      kind: String,
      epoch: Any = JSONObject.NULL,
      gen: Any = JSONObject.NULL,
      baselineGen: Any = JSONObject.NULL
    ) = JSONObject()
      .put("raw", raw)
      .put("kind", kind)
      .put("epoch", epoch)
      .put("gen", gen)
      .put("baselineGen", baselineGen)

    fun expectation(
      delivered: JSONArray = JSONArray(),
      suppressed: JSONArray = JSONArray()
    ) = JSONObject()
      .put("deliveredRecords", delivered)
      .put("suppressedSurfaceRecords", suppressed)
      .put(
        "detachedNonSurfaceBacklog",
        JSONObject().put("count", 0).put("bytes", 0)
      )
      .put("refreshRequestCount", 0)
      .put("capsProcessedCount", 0)
      .put("ignoredStaleCallbackCount", 0)
      .put("acceptedClientInputs", JSONArray())
      .put("discardedInboundChunks", JSONArray())
      .put("parser", JSONObject().put("token", 1).put("bufferedBytes", 0))
      .put(
        "connection",
        JSONObject()
          .put("currentToken", 1)
          .put("lastIssuedToken", 1)
          .put("phase", "active")
          .put("sceneInputFinished", false)
      )

    val valid = expectation(
      delivered = JSONArray().put(
        record(fullRaw, "full", epoch = 7, gen = 1)
      )
    )
    WebSurfaceConformanceHostSchema.validateChannelStep(
      entry,
      "expect",
      valid,
      "valid channel expectation"
    )

    val corruptions = listOf<(JSONObject) -> Unit>(
      { it.getJSONArray("deliveredRecords").getJSONObject(0).put("kind", "delta") },
      { it.getJSONArray("deliveredRecords").getJSONObject(0).put("epoch", 8) },
      { it.getJSONArray("deliveredRecords").getJSONObject(0).put("gen", 2) },
      { it.getJSONArray("deliveredRecords").getJSONObject(0).put("baselineGen", 0) }
    )
    for (corrupt in corruptions) {
      val changed = parseExactJSONObject(valid.toString(), "channel expectation clone")
      corrupt(changed)
      assertThrows(IllegalArgumentException::class.java) {
        WebSurfaceConformanceHostSchema.validateChannelStep(
          entry,
          "expect",
          changed,
          "invalid channel expectation"
        )
      }
    }

    val deltaWithWrongBaseline = expectation(
      delivered = JSONArray().put(
        record(deltaRaw, "delta", epoch = 7, gen = 2, baselineGen = 0)
      )
    )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateChannelStep(
        entry,
        "expect",
        deltaWithWrongBaseline,
        "wrong delta baseline expectation"
      )
    }

    val partialStampRaw =
      "\u001Esurface:" +
        """{"version":3,"encoding":"delta","epoch":7,"gen":2,"width":1,"height":1,"styles":[null],"deltaRows":[],"images":[]}""" +
        "\n"
    val partialRawStamps = expectation(
      delivered = JSONArray().put(
        record(partialStampRaw, "delta", epoch = 7, gen = 2)
      )
    )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateChannelStep(
        entry,
        "expect",
        partialRawStamps,
        "partial raw delta stamps"
      )
    }

    val nonSurfaceWithStamp = expectation(
      delivered = JSONArray().put(
        record(nonSurfaceRaw, "non-surface", epoch = 7)
      )
    )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateChannelStep(
        entry,
        "expect",
        nonSurfaceWithStamp,
        "stamped non-surface expectation"
      )
    }

    val suppressedNonSurface = expectation(
      suppressed = JSONArray().put(record(nonSurfaceRaw, "non-surface"))
    )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateChannelStep(
        entry,
        "expect",
        suppressedNonSurface,
        "suppressed non-surface expectation"
      )
    }

    val overlapRecord = record(fullRaw, "full", epoch = 7, gen = 1)
    val overlap = expectation(
      delivered = JSONArray().put(overlapRecord),
      suppressed = JSONArray().put(
        parseExactJSONObject(overlapRecord.toString(), "overlap clone")
      )
    )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateChannelStep(
        entry,
        "expect",
        overlap,
        "overlapping channel expectation"
      )
    }
  }

  @Test
  fun integerSchemaRejectsFractionalBigDecimalsAndNegativeUnderflowExactly() {
    val fractional = parseExactJSONObject(
      """{"value":1.0000000000000000001}""",
      "fractional integer"
    )
    assertThrows(IllegalArgumentException::class.java) {
      fractional.requiredLong("value", "fractional integer")
    }

    val negativeUnderflow = parseExactJSONObject(
      """{"value":-1e-10000}""",
      "negative underflow integer"
    )
    assertThrows(IllegalArgumentException::class.java) {
      negativeUnderflow.requiredLong("value", "negative underflow integer")
    }

    val hostIntFraction = parseExactJSONObject(
      """{"value":2147483647.0000000001}""",
      "fractional host Int"
    )
    assertThrows(IllegalArgumentException::class.java) {
      hostIntFraction.requiredInt("value", "fractional host Int")
    }
  }

  @Test
  fun styleRunSchemaRejectsOverlapPartialCellsGapCrossingTextDriftAndMissedCoalescing() {
    fun cell(column: Int, text: String, span: Int) =
      JSONObject().put("column", column).put("text", text).put("span", span)
    val rows = JSONArray().put(
      JSONObject()
        .put("row", 0)
        .put(
          "cells",
          JSONArray()
            .put(cell(column = 0, text = "A", span = 1))
            .put(cell(column = 1, text = "B", span = 1))
            .put(cell(column = 3, text = "界", span = 2))
        )
    )
    fun run(column: Int, text: String, span: Int, color: String = "#E05757FF") =
      JSONObject()
        .put("row", 0)
        .put("startColumn", column)
        .put("text", text)
        .put("span", span)
        .put("resolvedStyle", JSONObject().put("fg", color))

    val valid = JSONArray()
      .put(run(column = 0, text = "AB", span = 2))
      .put(run(column = 3, text = "界", span = 2))
    WebSurfaceConformanceLoader.validateStyleRuns(valid, rows, "valid style runs")

    val invalid = listOf(
      "overlap" to JSONArray()
        .put(run(column = 0, text = "AB", span = 2))
        .put(run(column = 1, text = "B", span = 1, color = "#5BA3FFFF")),
      "mid-cell start" to JSONArray().put(run(column = 4, text = "界", span = 1)),
      "partial cell" to JSONArray().put(run(column = 3, text = "界", span = 1)),
      "cell overrun" to JSONArray().put(run(column = 3, text = "界", span = 3)),
      "gap crossing" to JSONArray().put(run(column = 0, text = "AB界", span = 5)),
      "text drift" to JSONArray().put(run(column = 0, text = "AX", span = 2)),
      "missed coalescing" to JSONArray()
        .put(run(column = 0, text = "A", span = 1))
        .put(run(column = 1, text = "B", span = 1))
    )
    invalid.forEach { (axis, runs) ->
      assertThrows(axis, IllegalArgumentException::class.java) {
        WebSurfaceConformanceLoader.validateStyleRuns(runs, rows, axis)
      }
    }
  }

  @Test
  fun geometrySchemaRejectsHostIntOverflowWithoutNarrowing() {
    val overflowingGrid = JSONArray().put(
      JSONObject()
        .put("row", 0)
        .put(
          "cells",
          JSONArray().put(
            JSONObject()
              .put("column", Int.MAX_VALUE)
              .put("text", "X")
              .put("span", 1)
          )
        )
    )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateGrid(
        overflowingGrid,
        "overflowing grid",
        width = Int.MAX_VALUE,
        height = 1
      )
    }

    val overflowingDamage = JSONObject()
      .put("action", "publish")
      .put("sequence", 1)
      .put("width", Int.MAX_VALUE)
      .put("height", 1)
      .put("rows", JSONArray())
      .put(
        "damage",
        JSONObject().put(
          "rows",
          JSONArray().put(
            JSONObject()
              .put("row", 0)
              .put(
                "ranges",
                JSONArray().put(
                  JSONArray().put(0).put(Int.MAX_VALUE.toLong() + 1L)
                )
              )
          )
        )
      )
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceHostSchema.validateAndroidABIStep(
        "androidABI",
        overflowingDamage,
        "overflowing damage"
      )
    }

    val value = JSONObject().put("value", Int.MAX_VALUE.toLong() + 1L)
    assertThrows(IllegalArgumentException::class.java) {
      value.requiredInt("value", "overflowing host Int")
    }
  }

  @Test
  fun integrityMetaTestsRejectManifestHashBodyHashAndHashedBodyByteBeforeExecution() {
    val pristine = resourceMap()
    val target = "conformance-control.jsonl"

    val wrongManifestHeader = pristine.toMutableMap()
    wrongManifestHeader[target] = pristine.getValue(target).asUTF8()
      .replaceFirst(
        Regex(""""manifestSHA256":"[0-9a-f]{64}""""),
        """"manifestSHA256":"${"0".repeat(64)}""""
      )
      .encodeToByteArray()
    assertCorpusRejected(pristine.keys, wrongManifestHeader, "manifest hash")

    val wrongBodyHeader = pristine.toMutableMap()
    wrongBodyHeader[target] = pristine.getValue(target).asUTF8()
      .replaceFirst(
        Regex(""""bodySHA256":"[0-9a-f]{64}""""),
        """"bodySHA256":"${"0".repeat(64)}""""
      )
      .encodeToByteArray()
    assertCorpusRejected(pristine.keys, wrongBodyHeader, "body hash")

    val changedBody = pristine.toMutableMap()
    changedBody[target] = pristine.getValue(target).asUTF8()
      .replaceFirst("""\"A\"""", """\"Q\"""")
      .encodeToByteArray()
    assertCorpusRejected(pristine.keys, changedBody, "hashed body byte")
  }

  @Test
  fun rawTextMetaTestsRejectBomCrInvalidUTF8MissingTerminalLFAndBlankLines() {
    val pristine = resourceMap()
    val manifest = pristine.getValue("conformance-manifest.json")
    val variants = linkedMapOf(
      "BOM" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + manifest,
      "CR" to manifest.asUTF8().replaceFirst("\n", "\r\n").encodeToByteArray(),
      "invalid UTF-8" to manifest.copyOf().also { it[0] = 0x80.toByte() },
      "terminal LF" to manifest.copyOfRange(0, manifest.lastIndex),
      "blank line" to manifest.asUTF8().replaceFirst("\n", "\n\n").encodeToByteArray(),
      "NUL" to
        manifest.copyOfRange(0, manifest.lastIndex) +
        byteArrayOf(0x00, 0x0A)
    )

    for ((axis, bytes) in variants) {
      val resources = pristine.toMutableMap()
      resources["conformance-manifest.json"] = bytes
      assertCorpusRejected(pristine.keys, resources, axis)
    }
  }

  @Test
  fun censusMetaTestsRejectMissingAndExtraMirroredFixtures() {
    val pristine = resourceMap()
    val declared = pristine.keys.filterTo(sortedSetOf()) {
      it.startsWith("conformance-") && it.endsWith(".jsonl")
    }
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceLoader.load(
        resourceBytes = pristine::getValue,
        resourceCensus = { declared - "conformance-control.jsonl" }
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      WebSurfaceConformanceLoader.load(
        resourceBytes = pristine::getValue,
        resourceCensus = { declared + "conformance-extra.jsonl" }
      )
    }
  }

  private fun assertCorpusRejected(
    resourceNames: Set<String>,
    resources: Map<String, ByteArray>,
    message: String
  ) {
    val census = resourceNames.filterTo(sortedSetOf()) {
      it.startsWith("conformance-") && it.endsWith(".jsonl")
    }
    assertThrows(message, IllegalArgumentException::class.java) {
      WebSurfaceConformanceLoader.load(
        resourceBytes = resources::getValue,
        resourceCensus = { census }
      )
    }
  }

  private fun resourceMap(): Map<String, ByteArray> {
    val manifestBytes = readResource("conformance-manifest.json")
    val manifest = parseExactJSONObject(manifestBytes.asUTF8(), "test manifest")
    val result = linkedMapOf("conformance-manifest.json" to manifestBytes)
    val fixtures = manifest.getJSONArray("fixtures")
    for (index in 0 until fixtures.length()) {
      val file = fixtures.getJSONObject(index).getString("file")
      result[file] = readResource(file)
    }
    return result
  }

  private fun readResource(name: String): ByteArray =
    requireNotNull(requireNotNull(javaClass.classLoader).getResourceAsStream(name)) {
      "missing test resource $name"
    }.use { it.readBytes() }

  private fun ByteArray.asUTF8(): String = toString(StandardCharsets.UTF_8)
}
