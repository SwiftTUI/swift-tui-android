package sh.swifttui.android.host

import org.json.JSONArray
import org.json.JSONException
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
  fun lenientJsonSyntaxCannotHideNestingFromAdmission() {
    val nested = "[".repeat(10000) + "0" + "]".repeat(10000)
    val hostile = listOf(
      """{"future":'"',"deep":$nested}""",
      """{/* " */ "deep":$nested}""",
      "{// \"\n\"deep\":$nested}",
      "{# \"\n\"deep\":$nested}",
      """{"future":bare"quote,"deep":$nested}""",
    )
    for (json in hostile) {
      val session = SwiftTUIWebSurfaceSession()
      session.decode(wire(base()))
      assertNull(session.decode(SwiftTUIWebSurfaceSession.RECORD_PREFIX + json))
      assertEquals("keyframe", session.pendingResyncScope)
      assertEquals(2L, session.decode(wire(base()))!!.consumedGeneration)
    }
    // Quotes, apostrophes and comment markers remain ordinary string content.
    val valid = base().put("future", "https://host/'quoted' # /* \\\" */")
    assertNotNull(SwiftTUIWebSurfaceSession().decode(wire(valid)))
  }

  @Test
  fun nonJsonNumericTokensAreRefusedBeforeRetention() {
    // HOST-WIRE-CONTRACT: a surface payload is a JSON object, and RFC 8259 §6
    // numbers have no NaN or Infinity. The browser decoders and the Swift
    // reference reject these records, which org.json would otherwise coerce
    // (X1-053, X1-054; STUI-1145).
    val refused = Admission(accepted = false, opacity = null, resync = "keyframe")
    val x1 = listOf("NaN", "Infinity").map { token ->
      val session = SwiftTUIWebSurfaceSession()
      val frame = session.decode(
        surface(
          """{"version":1,"width":4,"height":1,"styles":[null,{"opacity":$token,"fg":"#FFFFFFFF"}],""" +
            """"rows":[[[0,"B",1,1]]],"epoch":700,"gen":1}"""
        )
      )
      token to Admission(frame != null, frame?.cells?.single()?.style?.opacity, session.pendingResyncScope)
    }
    val tokens = listOf("NaN", "Infinity", "-Infinity", "nan", "+1", ".5", "1.", "01", "0x10", "1.5f", "1d")
    // A stamped delta is refused the same way. While that keyframe repair is
    // pending, a valid delta naming the retained baseline does not apply.
    fun delta(opacity: String) = surface(
      """{"version":3,"encoding":"delta","epoch":7,"gen":2,"baselineGen":1,"width":1,"height":2,""" +
        """"styles":[null,{"opacity":$opacity}],"deltaRows":[[0,[[0,"b",1,1]]]]}"""
    )
    assertEquals(
      Triple(
        listOf("NaN" to refused, "Infinity" to refused),
        tokens.map { it to (listOf(refused) to 2L) },
        listOf(refused, refused) to 2L
      ),
      Triple(
        x1,
        tokens.map { token -> token to admissions(styledFull(token)) },
        admissions(delta("NaN"), delta("0.5"))
      )
    )
  }

  @Test
  fun nonJsonTokensAreRefusedInEveryRecordPosition() {
    // One lexical rule covers every value, including additive keys, and the
    // remaining org.json extensions at that seam: unquoted keys and words,
    // case variants of the literal names, and non-JSON whitespace.
    val members = listOf(
      """"images":[{"id":"i","format":"future","bounds":[0,0,0,0],"visibleBounds":[0,0,0,0],""" +
        """"scalingMode":"stretch","opacity":NaN}]""",
      """"accessibilityTree":[{"id":"n","rect":[Infinity,0,1,1],"role":"group"}]""",
      """"future":NaN""",
      """"future":{"ratio":[-Infinity]}""",
      """future:1""",
      """"future":TRUE""",
      """"future":Null""",
      """"future":abc""",
      "\"future\":\u000C1",
      "\"future\":true ",
    )
    val refused = Admission(accepted = false, opacity = null, resync = "keyframe")
    assertEquals(
      members.map { it to (listOf(refused) to 2L) },
      members.map { member ->
        member to admissions(
          surface(
            """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],""" +
              """"rows":[[[0,"b",1,0]],[]],$member}"""
          )
        )
      }
    )
  }

  @Test
  fun jsonNumbersLiteralsAndWhitespaceStayAdmitted() {
    // Every RFC 8259 number form, including the producer's `1e-05` spelling.
    val spellings = linkedMapOf(
      "0.5" to 0.5, "5E-1" to 0.5, "5e-1" to 0.5, "0.5e0" to 0.5, "0.05E+1" to 0.5,
      "1e-05" to 1e-5, "0" to 0.0, "-0" to 0.0, "1" to 1.0
    )
    for ((token, value) in spellings) {
      val (observed, repairedGeneration) = admissions(styledFull(token))
      val admission = observed.single()
      assertTrue(token, admission.accepted)
      assertEquals(token, value, requireNotNull(admission.opacity), 0.0)
      assertNull(token, admission.resync)
      assertEquals(token, 3L, repairedGeneration)
    }
    // Literal names and all four JSON whitespace characters, including CR LF.
    val compact =
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null,{"opacity":0.5}],""" +
        """"rows":[[[0,"b",1,1]],[]],"accessibilityTree":[{"id":"n","parentId":null,""" +
        """"rect":[0,0,1,1],"role":"group","hidden":true,"isFocused":false}]}"""
    val spaced = compact.replace("{", "{ ").replace("[", "[\t").replace(",", " ,\r\n").replace(":", "\t: ")
    val frames = listOf(compact, spaced).map {
      requireNotNull(SwiftTUIWebSurfaceSession().decode(surface(it)))
    }
    assertEquals(frames[0].cells, frames[1].cells)
    assertEquals(frames[0].accessibilityNodes, frames[1].accessibilityNodes)
    assertTrue(frames[1].accessibilityNodes.single().hidden)
    // Token spellings inside strings stay ordinary content.
    val content = SwiftTUIWebSurfaceSession().decode(
      surface(
        """{"version":2,"width":1,"height":1,"styles":[null],"rows":[[[0,"NaN",1,0]]],""" +
          """"future":"NaN Infinity -Infinity 0x10 TRUE abc 1.5f \f /* # '"}"""
      )
    )
    assertEquals("NaN", requireNotNull(content).cells.single().character)
  }

  @Test
  fun overflowingJsonExponentStaysAdmitted() {
    // X1-055: `1e400` is RFC 8259 number syntax. The contract states no
    // consumer range policy, so lexical admission leaves it to the parser
    // (the JVM org.json artifact retains Infinity; that probe stays uncounted).
    val session = SwiftTUIWebSurfaceSession()
    val frame = session.decode(
      surface(
        """{"version":1,"width":4,"height":1,"styles":[null,{"opacity":1e400,"fg":"#FFFFFFFF"}],""" +
          """"rows":[[[0,"B",1,1]]],"epoch":700,"gen":1}"""
      )
    )
    assertEquals("B", requireNotNull(frame).cells.single().character)
    assertNull(session.pendingResyncScope)
  }

  @Test
  fun structuralJsonExtensionsAreRefusedInEveryRecordPosition() {
    // RFC 8259 §4 and §5 (STUI-1277): keys are strings, and one separator
    // stands between members and between elements. org.json would take a
    // numeric or literal key as text, read `[1 2]` as one string, read an
    // elided element as null and drop a trailing separator.
    val members = listOf(
      "1:2", "true:1", "null:1", "-0:1",
      """"future":[1 2]""", """"future":[,]""", """"future":[1,,]""", """"future":[1,,2]""",
      """"future":{"a":1,}""", """"future":[[],]""",
    )
    assertEquals(
      members.map { it to ("refused keyframe" to 2L) },
      members.map { it to outcome(surface("$FULL_BODY,$it}")) }
    )
  }

  @Test
  fun onlyJsonWhitespaceMayFollowTheSurfaceObject() {
    // RFC 8259 §2: a JSON text is one value. org.json would commit the first
    // object and ignore what follows it (STUI-1277). Stray closers were
    // already refused by the nesting count.
    val suffixes = listOf(",", ":", "1", "null", "\"x\"", "{", "[", "{}", " {\"a\":1}", "\n[]", "\r\n0", "}", "]")
    assertEquals(
      suffixes.map { it to ("refused keyframe" to 2L) },
      suffixes.map { it to outcome(surface("$FULL_BODY}$it")) }
    )
  }

  @Test
  fun grammarViolationsAreRefusedBeforeTheParserRuns() {
    // The same grammar check refuses malformed members that org.json rejects
    // by throwing, so they take the refusal path rather than an exception.
    val members = listOf(
      """"future" 1""", """"future"::1""", """"future":1 "more":2""", """"future":1:2""",
      """"future":[1:2]""", """"future":[1}""", """"future":{"a"}""", """"future":{,"a":1}""",
      ""","future":1""",
    )
    assertEquals(
      members.map { it to ("refused keyframe" to 2L) },
      members.map { it to outcome(surface("$FULL_BODY,$it}")) }
    )
  }

  @Test
  fun incompleteAndNonObjectPayloadsStayParserErrors() {
    // A payload that ends before its object closes, or a JSON value that is
    // not an object, still reaches org.json, which rejects it. No prefix of a
    // record is ever committed.
    val record = "$FULL_BODY}"
    val prefixes = (0 until record.length).map { outcome(surface(record.substring(0, it))) }
    assertEquals(
      setOf(2L) to emptyList<String>(),
      prefixes.mapTo(mutableSetOf()) { it.second } to
        prefixes.map { it.first }.filter { it != "parser error" && it != "refused keyframe" }
    )
    val cuts = listOf(
      "", "{", """{"version"""", """{"version":""", """{"version":2""", """{"version":2,""", """{"ver""",
      FULL_BODY, "[]", "\"x\"", "1", "null", """[{"version":2}]""",
    )
    assertEquals(
      cuts.map { it to ("parser error" to 2L) } + ("tr" to ("refused keyframe" to 2L)),
      cuts.map { it to outcome(surface(it)) } +
        ("tr" to outcome(surface("""{"version":2,"epoch":7,"gen":tr""")))
    )
  }

  @Test
  fun jsonStructureAndTrailingWhitespaceStayAdmitted() {
    val members = listOf(
      """"future":{}""", """"future":[]""", """"future":[[],[{}],{"b":[]}]""", """"":1""",
      """"future":{ }""", """"future":[ ]""", """"future" : [ 1 , { "k" : null } ]""",
      """"future":"{,}[,]:\",}"""", """"future":"\\"""",
    )
    val suffixes = listOf(" ", "\t", "\r\n", "\n\n", "  \r\n\t ")
    assertEquals(
      (members + suffixes).map { it to ("frame b" to 3L) },
      members.map { it to outcome(surface("$FULL_BODY,$it}")) } +
        suffixes.map { it to outcome(surface("$FULL_BODY}$it")) }
    )
  }

  /**
   * Decodes [record] over the stamped [base] baseline and names the outcome
   * (`frame <first cell>`, `refused <pending repair>` or `parser error`),
   * then decodes the baseline again and returns its consumed generation: 2
   * means the record did not advance the session.
   */
  private fun outcome(record: String): Pair<String, Long> {
    val session = SwiftTUIWebSurfaceSession()
    session.decode(wire(base()))
    val observed = try {
      session.decode(record)?.let { "frame ${it.cells.first().character}" }
        ?: "refused ${session.pendingResyncScope}"
    } catch (_: JSONException) {
      "parser error"
    }
    return observed to requireNotNull(session.decode(wire(base()))).consumedGeneration
  }

  private data class Admission(val accepted: Boolean, val opacity: Double?, val resync: String?)

  private fun surface(json: String): String = SwiftTUIWebSurfaceSession.RECORD_PREFIX + json + "\n"

  private companion object {
    /** A stamped full frame over [base] (generation 2, cell `b`) without its closing brace. */
    const val FULL_BODY =
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],"rows":[[[0,"b",1,0]],[]]"""
  }

  private fun styledFull(opacity: String): String = surface(
    """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null,{"opacity":$opacity}],""" +
      """"rows":[[[0,"b",1,1]],[]]}"""
  )

  /**
   * Decodes [records] over the stamped [base] baseline, observing each one,
   * then decodes the baseline again and returns its consumed generation: 2
   * means no record in between advanced the session.
   */
  private fun admissions(vararg records: String): Pair<List<Admission>, Long> {
    val session = SwiftTUIWebSurfaceSession()
    session.decode(wire(base()))
    val observed = records.map { record ->
      val frame = session.decode(record)
      Admission(frame != null, frame?.cells?.firstOrNull()?.style?.opacity, session.pendingResyncScope)
    }
    return observed to requireNotNull(session.decode(wire(base()))).consumedGeneration
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
