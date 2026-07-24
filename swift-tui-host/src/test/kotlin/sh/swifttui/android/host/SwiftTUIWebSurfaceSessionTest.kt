package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTUIWebSurfaceSessionTest {
  private val prefix = SwiftTUIWebSurfaceSession.RECORD_PREFIX

  @Test
  fun mapsAFullV2RecordOntoTheFrameModel() {
    val session = SwiftTUIWebSurfaceSession()
    val record = prefix +
      """{"version":2,"sequence":9,"width":4,"height":2,""" +
      """"styles":[null,{"fg":"#FF0000FF","em":17,"opacity":0.5,""" +
      """"underline":{"pattern":"double","color":"#00FF00FF"}}],""" +
      """"rows":[[[0,"a",1,1],[1,"b",1,0],[2,"c",1,0],[3," ",1,0]],""" +
      """[[0,"宽",2,0],[2,"e",1,0],[3," ",1,0]]],""" +
      """"images":[{"id":"png:abc:5","format":"png","bounds":[0,1,1,1],""" +
      """"visibleBounds":[0,1,1,1],"scalingMode":"fit","pixelSize":[2,3],""" +
      """"dataBase64":"aGk="}],""" +
      """"damage":{"textRows":[[1,[[0,2]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false},""" +
      """"accessibilityTree":[{"id":"root/field","parentId":"root",""" +
      """"rect":[0,0,4,1],"role":"textField","label":"Field","hidden":true,""" +
      """"liveRegion":"polite","cursorAnchor":[1,0],"isFocused":true}],""" +
      """"accessibilityAnnouncements":[{"message":"Ready","politeness":"assertive"}],""" +
      """"scrollRegions":[{"id":"root/list","rect":[0,0,4,2],"offset":[0,2],""" +
      """"content":[4,9]}],""" +
      """"links":[[0,[[0,2,0],[2,1,1]]]],""" +
      """"linkTargets":["https://a.example/docs","https://b.example"],""" +
      """"focusPresentation":{"focusedIdentity":"root/field","semantics":"edit",""" +
      """"prefersTextInput":true,"hasFocusedRegion":true},""" +
      """"preferredGridWidth":9,"preferredGridHeight":8,""" +
      """"terminalStyle":{"foregroundColor":{"hex":"#ECEFF4FF"},""" +
      """"backgroundColor":{"hex":"#1E222AFF"},"tintColor":{"hex":"#56B6C2FF"}}}""" + "\n"

    val frame = requireNotNull(session.decode(record))

    assertEquals(9L, frame.sequence)
    assertEquals(4, frame.gridWidth)
    assertEquals(2, frame.gridHeight)
    assertEquals(9, frame.preferredGridWidth)
    assertEquals(8, frame.preferredGridHeight)

    // Style table dereference + emphasis bitmask (17 = bold|reverse).
    val styled = requireNotNull(frame.cellAt(1, 1))
    assertEquals("a", styled.character)
    assertEquals(setOf("bold", "reverse"), requireNotNull(styled.style).emphasis)
    assertEquals("#FF0000FF", styled.style?.foregroundColor?.hex)
    assertEquals("double", styled.style?.underlineStyle?.pattern)
    assertEquals(0.5, requireNotNull(styled.style).opacity, 0.0)

    // Link runs expand onto lead cells: "ab" on target 0, "c" on target 1.
    assertEquals("https://a.example/docs", frame.cellAt(1, 1)?.hyperlink)
    assertEquals("https://a.example/docs", frame.cellAt(2, 1)?.hyperlink)
    assertEquals("https://b.example", frame.cellAt(3, 1)?.hyperlink)
    assertNull(frame.cellAt(4, 1)?.hyperlink)

    // A wide lead's span resolves taps on its covered column (no
    // continuation cells on the converged wire).
    assertEquals("宽", frame.cellAt(2, 2)?.character)

    assertEquals("#1E222AFF", frame.terminalStyle.backgroundColor.hex)

    val image = frame.imageAttachments.single()
    assertEquals("png:abc:5", image.id)
    assertEquals("aGk=", image.payloadBase64)
    assertEquals(SwiftTUIRect(0, 1, 1, 1), image.bounds)
    assertEquals(SwiftTUIPixelSize(2, 3), image.pixelSize)
    assertEquals("fit", image.scalingMode)

    val node = frame.accessibilityNodes.single()
    assertEquals("root/field", node.id)
    assertEquals("root", node.parentID)
    assertEquals(SwiftTUIRect(0, 0, 4, 1), node.rect)
    assertTrue(node.hidden)
    assertTrue(node.isFocused)
    assertEquals(SwiftTUIPoint(1, 0), node.cursorAnchor)

    assertEquals("assertive", frame.accessibilityAnnouncements.single().politeness)

    val region = frame.scrollRegions.single()
    assertEquals(SwiftTUIPoint(0, 2), region.offset)
    assertEquals(SwiftTUICellSize(4, 9), region.content)
    assertTrue(region.canScrollDown)

    // Damage: dirty rows derive from the text rows; booleans map through.
    assertEquals(listOf(1), frame.dirtyRows)
    assertEquals(
      listOf(SwiftTUIRange(0, 2)),
      frame.textDamageRows.single().columnRanges
    )
    assertEquals(1, frame.textDamageRows.single().row)
    assertEquals(false, frame.requiresFullTextRepaint)
    assertEquals(false, frame.requiresFullGraphicsReplay)

    assertEquals("root/field", frame.focusedIdentity)
    assertEquals("edit", frame.focusPresentation.semantics)
    assertTrue(frame.focusPresentation.prefersTextInput)
  }

  @Test
  fun v1RecordsDefaultTheOptionalSurfaces() {
    val session = SwiftTUIWebSurfaceSession()
    val record = prefix +
      """{"version":1,"width":2,"height":1,"styles":[null],""" +
      """"rows":[[[0,"O",1,0],[1,"K",1,0]]],"images":[]}""" + "\n"

    val frame = requireNotNull(session.decode(record))

    assertEquals(0L, frame.sequence)
    assertEquals(SwiftTUITerminalStyle.Default, frame.terminalStyle)
    assertEquals(SwiftTUIFocusPresentation.None, frame.focusPresentation)
    // Absent damage means full repaint, mirroring the legacy wire default.
    assertTrue(frame.requiresFullTextRepaint)
    assertTrue(frame.requiresFullGraphicsReplay)
    assertEquals("OK", frame.cells.joinToString("") { it.character })
  }

  @Test
  fun deltaRecordsReplaceRowsOverTheBaseline() {
    val session = SwiftTUIWebSurfaceSession()
    session.decode(fullRecord(sequence = 1, rowText = listOf("ab", "cd")))

    val delta = prefix +
      """{"version":3,"encoding":"delta","sequence":2,"width":2,"height":2,""" +
      """"styles":[null],"deltaRows":[[1,[[0,"X",1,0],[1,"Y",1,0]]]],"images":[],""" +
      """"damage":{"textRows":[[1,[[0,2]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false}}""" + "\n"
    val frame = requireNotNull(session.decode(delta))

    assertEquals(2L, frame.sequence)
    assertEquals("a", frame.cellAt(1, 1)?.character)
    assertEquals("b", frame.cellAt(2, 1)?.character)
    assertEquals("X", frame.cellAt(1, 2)?.character)
    assertEquals("Y", frame.cellAt(2, 2)?.character)
    assertEquals(listOf(1), frame.dirtyRows)

    // The materialized frame becomes the next baseline.
    val second = prefix +
      """{"version":3,"encoding":"delta","sequence":3,"width":2,"height":2,""" +
      """"styles":[null],"deltaRows":[[0,[[0,"Z",1,0],[1,"b",1,0]]]],"images":[],""" +
      """"damage":{"textRows":[[0,[[0,1]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false}}""" + "\n"
    val next = requireNotNull(session.decode(second))
    assertEquals("Z", next.cellAt(1, 1)?.character)
    assertEquals("X", next.cellAt(1, 2)?.character)
  }

  @Test
  fun fullRecordsAfterDeltasBecomeTheNewBaseline() {
    val session = SwiftTUIWebSurfaceSession()
    val redThenBlue = """[null,{"fg":"#FF0000FF"},{"fg":"#0000FFFF"}]"""
    val blueThenRed = """[null,{"fg":"#0000FFFF"},{"fg":"#FF0000FF"}]"""

    val firstFull = prefix +
      """{"version":2,"sequence":1,"width":2,"height":1,"styles":$redThenBlue,""" +
      """"rows":[[[0,"A",1,1],[1,"B",1,2]]],"images":[]}""" + "\n"
    requireNotNull(session.decode(firstFull))

    val firstDelta = prefix +
      """{"version":3,"encoding":"delta","sequence":2,"width":2,"height":1,""" +
      """"styles":$redThenBlue,""" +
      """"deltaRows":[[0,[[0,"C",1,2],[1,"D",1,1]]]],"images":[]}""" + "\n"
    requireNotNull(session.decode(firstDelta))

    val secondFull = prefix +
      """{"version":2,"sequence":3,"width":2,"height":1,"styles":$blueThenRed,""" +
      """"rows":[[[0,"E",1,1],[1,"F",1,2]]],"images":[]}""" + "\n"
    requireNotNull(session.decode(secondFull))

    val finalDelta = prefix +
      """{"version":3,"encoding":"delta","sequence":4,"width":2,"height":1,""" +
      """"styles":$blueThenRed,""" +
      """"deltaRows":[[0,[[0,"G",1,2],[1,"H",1,1]]]],"images":[]}""" + "\n"
    val frame = requireNotNull(session.decode(finalDelta))

    assertEquals("G", frame.cellAt(1, 1)?.character)
    assertEquals("#FF0000FF", frame.cellAt(1, 1)?.style?.foregroundColor?.hex)
    assertEquals("H", frame.cellAt(2, 1)?.character)
    assertEquals("#0000FFFF", frame.cellAt(2, 1)?.style?.foregroundColor?.hex)
  }

  @Test
  fun deltaGuardsMirrorTheBrowserDecoder() {
    val session = SwiftTUIWebSurfaceSession()
    val delta = prefix +
      """{"version":3,"encoding":"delta","sequence":2,"width":2,"height":2,""" +
      """"styles":[null],"deltaRows":[[0,[[0,"X",1,0]]]],"images":[],""" +
      """"damage":{"textRows":[[0,[[0,1]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false}}""" + "\n"

    // No baseline yet: the delta is dropped, not an error.
    assertNull(session.decode(delta))

    // A size-mismatched baseline drops the delta too.
    session.decode(fullRecord(sequence = 1, rowText = listOf("abc")))
    assertNull(session.decode(delta))

    // A row index outside the grid is rejected.
    session.decode(fullRecord(sequence = 3, rowText = listOf("ab", "cd")))
    val badRow = prefix +
      """{"version":3,"encoding":"delta","sequence":4,"width":2,"height":2,""" +
      """"styles":[null],"deltaRows":[[7,[[0,"X",1,0]]]],"images":[],""" +
      """"damage":{"textRows":[[0,[[0,1]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false}}""" + "\n"
    assertNull(session.decode(badRow))
  }

  @Test
  fun consumptionGenerationsStayContiguousAcrossSequenceGaps() {
    val session = SwiftTUIWebSurfaceSession()
    val first = requireNotNull(session.decode(fullRecord(sequence = 5, rowText = listOf("ab", "cd"))))
    val delta = prefix +
      """{"version":3,"encoding":"delta","sequence":9,"width":2,"height":2,""" +
      """"styles":[null],"deltaRows":[[0,[[0,"X",1,0],[1,"b",1,0]]]],"images":[],""" +
      """"damage":{"textRows":[[0,[[0,1]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false}}""" + "\n"
    val second = requireNotNull(session.decode(delta))

    // The converged wire's damage is consumption-relative (the Swift host
    // accumulates it across skipped polls), so the renderer's partial-repaint
    // guard follows consumption contiguity, not the gapped sequence.
    assertEquals(first.consumedGeneration + 1, second.consumedGeneration)
    val plan = SwiftTUIDamagePlan.plan(
      second,
      previousSequence = first.consumedGeneration,
      sizeChanged = false
    )
    assertEquals(false, plan.fullRepaint)
    assertEquals(listOf(0), plan.rows.map { it.row })
  }

  @Test
  fun newerVersionsFailLoudly() {
    val session = SwiftTUIWebSurfaceSession()
    val record = prefix + """{"version":4,"width":1,"height":1}""" + "\n"

    val error = runCatching { session.decode(record) }.exceptionOrNull()

    assertTrue(error is IllegalArgumentException)
    assertTrue(error?.message.orEmpty().contains("version 4"))
  }

  @Test
  fun resetDropsTheDeltaBaseline() {
    val session = SwiftTUIWebSurfaceSession()
    session.decode(fullRecord(sequence = 1, rowText = listOf("ab", "cd")))
    session.reset()

    val delta = prefix +
      """{"version":3,"encoding":"delta","sequence":2,"width":2,"height":2,""" +
      """"styles":[null],"deltaRows":[[0,[[0,"X",1,0]]]],"images":[],""" +
      """"damage":{"textRows":[[0,[[0,1]]]],"requiresFullTextRepaint":false,""" +
      """"requiresFullGraphicsReplay":false}}""" + "\n"
    assertNull(session.decode(delta))
  }

  @Test
  fun parsesTheSharedCanonicalTotalityFixtureFromTheSwiftEncoder() {
    // web-surface-totality.txt is generated by swift-tui's
    // WebSurfaceWireTotalityTests, mirrored here, and byte-compared across
    // repos by the coordination root's transport_fixture_sync gate — the
    // converged wire's cross-repo anchor: the browser decoder parses the
    // SAME bytes in swift-tui-web.
    val fixture = requireNotNull(
      javaClass.getResourceAsStream("/web-surface-totality.txt")
    ) {
      "missing web-surface-totality.txt test resource"
    }.bufferedReader().readText().replace("\\u001E", "\u001E")

    val frame = requireNotNull(SwiftTUIWebSurfaceSession().decode(fixture))

    assertEquals(99L, frame.sequence)
    assertEquals(4, frame.gridWidth)
    assertEquals(2, frame.gridHeight)
    assertEquals(9, frame.preferredGridWidth)
    assertEquals(8, frame.preferredGridHeight)
    assertEquals("https://a.example/docs", frame.cellAt(1, 1)?.hyperlink)
    assertEquals("https://b.example", frame.cellAt(3, 1)?.hyperlink)
    // The wide lead on row 1 spans its continuation column.
    assertEquals("宽", frame.cellAt(2, 2)?.character)
    assertEquals("edit", frame.focusPresentation.semantics)
    assertTrue(frame.focusPresentation.prefersTextInput)
    assertEquals("root/field", frame.focusedIdentity)
    assertTrue(frame.accessibilityNodes.single().hidden)
    assertEquals("root", frame.accessibilityNodes.single().parentID)
    assertEquals(1, frame.scrollRegions.size)
    // Topmost-region hit-testing (1-based coordinates), formerly covered by
    // the retired legacy-parser suite.
    assertEquals(frame.scrollRegions.single(), frame.scrollRegionAt(2, 1))
    assertEquals(null, frame.scrollRegionAt(5, 1))
    assertEquals("#708090FF", frame.terminalStyle.tintColor.hex)
    assertEquals("#405060FF", frame.terminalStyle.backgroundColor.hex)
    assertEquals(false, frame.requiresFullTextRepaint)
    assertEquals(1, frame.imageAttachments.size)
    assertTrue(frame.imageAttachments.single().payloadBase64 != null)
  }

  @Test
  fun parsesTheCanonicalCompositedImageFixtureFromTheSwiftEncoder() {
    // The pre-blend contract on the converged wire: the compositing-tagged
    // attachment arrives as the blended PNG payload under its stable
    // blend:png: content-hash id.
    val fixture = requireNotNull(
      javaClass.getResourceAsStream("/web-surface-composited-image.txt")
    ) {
      "missing web-surface-composited-image.txt test resource"
    }.bufferedReader().readText().replace("\\u001E", "\u001E")

    val frame = requireNotNull(SwiftTUIWebSurfaceSession().decode(fixture))

    val image = frame.imageAttachments.single()
    assertTrue(image.id.startsWith("blend:png:"))
    assertTrue(image.payloadBase64 != null)
    assertEquals(SwiftTUIPixelSize(2, 2), image.pixelSize)
    assertEquals("fit", image.scalingMode)
  }

  private fun fullRecord(sequence: Long, rowText: List<String>): String {
    val rows = rowText.joinToString(",", prefix = "[", postfix = "]") { text ->
      text.mapIndexed { x, character ->
        """[$x,"$character",1,0]"""
      }.joinToString(",", prefix = "[", postfix = "]")
    }
    val width = rowText.maxOf { it.length }
    return prefix +
      """{"version":2,"sequence":$sequence,"width":$width,""" +
      """"height":${rowText.size},"styles":[null],"rows":$rows,"images":[]}""" + "\n"
  }
}
