package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwiftTUIWireBudgetTest {
  private fun base(): JSONObject = JSONObject(
    """{"version":2,"epoch":7,"gen":1,"width":1,"height":2,"styles":[null],"rows":[[[0,"a",1,0]],[]]}"""
  )
  private fun wire(frame: JSONObject): String = SwiftTUIWebSurfaceSession.RECORD_PREFIX + frame.toString() + "\n"
  private fun padded(bytes: Int): String = "é".repeat(bytes / 2) + "x".repeat(bytes % 2)
  private fun array(count: Int, value: () -> Any): JSONArray = JSONArray().apply {
    repeat(count) { put(value()) }
  }

  @Test
  fun sharedBoundariesAndRecovery() {
    val fixture = javaClass.classLoader!!.getResourceAsStream("wire-budget-boundaries.json")!!
      .bufferedReader().use { JSONObject(it.readText()) }
    val cases = fixture.getJSONArray("cases")
    for (index in 0 until cases.length()) {
      val entry = cases.getJSONObject(index)
      val frame = base()
      val kind = entry.getString("kind")
      val value = entry.optInt("value")
      when (kind) {
        "recordBytes" -> {
          frame.put("future", "")
          frame.put("future", padded(value - wire(frame).toByteArray(Charsets.UTF_8).size + 1))
        }
        "width" -> frame.put("width", value)
        "grid" -> frame.put("width", entry.getInt("width")).put("height", entry.getInt("height"))
        "cellTextBytes" -> frame.put("rows", JSONArray().put(JSONArray().put(
          JSONArray().put(0).put(padded(value)).put(1).put(0))).put(JSONArray()))
        "styleBytes" -> frame.put("styles", JSONArray().put(JSONObject().put("future", padded(value - 22))))
        "styles" -> frame.put("styles", array(value) { JSONObject.NULL })
        "images" -> frame.put("images", array(value) { JSONObject(
          """{"id":"i","format":"future","bounds":[0,0,0,0],"visibleBounds":[0,0,0,0],"scalingMode":"stretch"}"""
        ) })
        "rows" -> frame.put("rows", array(value) { JSONArray() })
        "metadataEntries" -> frame.put("linkTargets", array(value) { "" })
        "imageDimension", "imagePixels", "imageIdBytes" -> frame.put("images", JSONArray().put(JSONObject()
          .put("id", if (kind == "imageIdBytes") padded(value) else "i").put("format", "future")
          .put("bounds", JSONArray("[0,0,0,0]")).put("visibleBounds", JSONArray("[0,0,0,0]"))
          .put("scalingMode", "stretch").put("pixelSize", when (kind) {
            "imagePixels" -> JSONArray().put(entry.getInt("width")).put(entry.getInt("height"))
            "imageDimension" -> JSONArray().put(value).put(1)
            else -> JSONArray("[1,1]")
          })))
        "deltaRow" -> {
          frame.remove("rows")
          frame.put("version", 3).put("encoding", "delta").put("gen", 2).put("baselineGen", 1)
            .put("deltaRows", JSONArray().put(JSONArray().put(value).put(JSONArray())))
        }
      }
      var record = wire(frame)
      if (kind == "jsonDepth") record = record.dropLast(2) + ",\"future\":" +
        "[".repeat(value - 1) + "0" + "]".repeat(value - 1) + "}\n"
      val session = SwiftTUIWebSurfaceSession()
      session.decode(wire(base()))
      val decoded = session.decode(record)
      assertEquals(entry.toString(), entry.getBoolean("accepted"), decoded != null)
      if (decoded == null) {
        assertEquals("keyframe", session.pendingResyncScope)
        assertEquals(2L, session.decode(wire(base()))!!.consumedGeneration)
        assertNull(session.pendingResyncScope)
      }
    }
  }

  @Test
  fun oversizedSizeQueriesAndRetrySizesNeverAllocateOrRequestRepeatedly() {
    for (oversizedOnRetry in listOf(false, true)) {
      var copies = 0
      var requests = 0
      val session = SwiftTUIWebSurfaceSession()
      val poller = SwiftTUIFramePoller(session, { _, bytes, _ ->
        if (bytes == null && oversizedOnRetry) 16 else {
          if (bytes != null) copies++
          Int.MAX_VALUE
        }
      }, { _, _, _ -> requests++; 1 })
      repeat(3) { assertTrue(poller.poll(1) is SwiftTUIFramePollResult.Error) }
      assertEquals(if (oversizedOnRetry) 3 else 0, copies)
      assertEquals(1, requests)
    }
  }

  @Test
  fun failedStyleAppendRetainsBaselineUntilKeyframeRecovery() {
    val session = SwiftTUIWebSurfaceSession()
    session.decode(wire(base().put("styles", array(1024) { JSONObject.NULL })))
    val delta = JSONObject("""{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":1,"height":2,"stylesBase":1024,"styles":[null],"deltaRows":[]}""")
    assertNull(session.decode(wire(delta)))
    assertEquals("keyframe", session.pendingResyncScope)
    assertNull(session.decode(wire(delta.put("styles", JSONArray()))))
    val repaired = session.decode(wire(base()))!!
    assertEquals(2L, repaired.consumedGeneration)
    assertEquals("a", repaired.cells.first().character)
  }

  @Test
  fun producerBudgetRefusalIsReportedWithoutALegacyVersionDiagnosis() {
    val record = "\u001EruntimeIssue:{\"code\":\"surface.budgetExceeded\"}\n".toByteArray()
    var requests = 0
    val poller = SwiftTUIFramePoller(SwiftTUIWebSurfaceSession(), { _, buffer, _ ->
      if (buffer != null) record.copyInto(buffer)
      record.size
    }, { _, _, _ -> requests++; 1 })
    repeat(2) {
      val error = poller.poll(1) as SwiftTUIFramePollResult.Error
      assertTrue(error.message.contains("allocation budget"))
      assertFalse(error.message.contains("legacy"))
    }
    assertEquals(1, requests)
  }

  @Test
  fun denseLargeGridAndOverflowingLinkSpan() {
    val session = SwiftTUIWebSurfaceSession()
    val row = JSONArray().apply { repeat(256) { put(JSONArray().put(it).put("a").put(1).put(0)) } }
    val frame = base().put("width", 256).put("height", 256).put("rows", array(256) { row })
    assertEquals(65536, session.decode(wire(frame))!!.cells.size)
    frame.put("links", JSONArray("""[[0,[[0,2147483647,0]]]]""")).put("linkTargets", JSONArray().put("url"))
    assertNull(session.decode(wire(frame)))
    assertNotNull(session.decode(wire(base())))
  }
}
