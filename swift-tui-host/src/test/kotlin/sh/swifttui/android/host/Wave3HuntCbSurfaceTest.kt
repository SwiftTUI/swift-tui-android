package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wave-3 regression hunt, part CB (STUI-1167): neighbours of the STUI-1145
 * strict-JSON admission. The coordination root's wave-3 hunt evidence owns
 * the case descriptions (w3cb011 to w3cb015). STUI-1277 made admission an
 * RFC 8259 grammar check, so w3cb011 to w3cb013 are ordinary assertions.
 *
 * Contract: README.md ("Surface records must also be strict JSON");
 * HOST-WIRE-CONTRACT.md (a `surface` payload is a JSON object); RFC 8259 §2,
 * §4, §5. Browser `JSON.parse` retains no surface for any of these records.
 */
class Wave3HuntCbSurfaceTest {
  @Test
  fun w3cb011SurfaceRecordWithATrailingMemberSeparatorIsRefused() {
    val trailing = surface(
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],""" +
        """"rows":[[[0,"b",1,0]],[]],}"""
    )
    assertEquals(listOf(REFUSED) to 2L, admissions(trailing))
  }

  @Test
  fun w3cb012ElidedAndTrailingArraySeparatorsAreRefused() {
    val elided = surface(
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[,{"opacity":0.5}],""" +
        """"rows":[[[0,"b",1,1]],[]]}"""
    )
    val trailing = surface(
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null,{"opacity":0.5},],""" +
        """"rows":[[[0,"b",1,1]],[]]}"""
    )
    assertEquals(
      listOf(listOf(REFUSED) to 2L, listOf(REFUSED) to 2L),
      listOf(admissions(elided), admissions(trailing))
    )
  }

  @Test
  fun w3cb013ContentAfterTheSurfaceObjectIsRefused() {
    val frame =
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],"rows":[[[0,"b",1,0]],[]]}"""
    val second =
      """{"version":2,"epoch":7,"gen":3,"width":1,"height":2,"styles":[null],"rows":[[[0,"z",1,0]],[]]}"""
    assertEquals(
      listOf(listOf(REFUSED) to 2L, listOf(REFUSED) to 2L),
      listOf(admissions(surface(frame + second)), admissions(surface("$frame []")))
    )
  }

  @Test
  fun w3cb014SemicolonMemberSeparatorIsRefusedBeforeParsing() {
    val record = surface(
      """{"version":2;"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],"rows":[[[0,"b",1,0]],[]]}"""
    )
    assertEquals(listOf(Admission(false, null, "keyframe")) to 2L, admissions(record))
  }

  @Test
  fun w3cb015ByteOrderMarkIsRefusedWithoutAnError() {
    val body = """"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],"rows":[[[0,"b",1,0]],[]]}"""
    val leading = surface("\uFEFF{\"version\":2,$body")
    val between = surface("{\"version\":2,\uFEFF$body")
    val refused = Admission(false, null, "keyframe")
    assertEquals(
      listOf(listOf(refused) to 2L, listOf(refused) to 2L),
      listOf(admissions(leading), admissions(between))
    )
  }

  private data class Admission(val accepted: Boolean, val text: String?, val resync: String?)

  private companion object {
    /** Refused before parsing: no frame, and one keyframe repair pending. */
    val REFUSED = Admission(false, null, "keyframe")
  }

  private fun surface(json: String): String = SwiftTUIWebSurfaceSession.RECORD_PREFIX + json + "\n"

  private fun base(): String = surface(
    """{"version":2,"epoch":7,"gen":1,"width":1,"height":2,"styles":[null],"rows":[[[0,"a",1,0]],[]]}"""
  )

  /**
   * Decodes [records] over the stamped baseline, then the baseline again, and
   * returns each admission with the baseline's consumed generation: 2 means
   * no record in between advanced the session.
   */
  private fun admissions(vararg records: String): Pair<List<Admission>, Long> {
    val session = SwiftTUIWebSurfaceSession()
    session.decode(base())
    val observed = records.map { record ->
      val frame = session.decode(record)
      Admission(frame != null, frame?.cells?.firstOrNull()?.character, session.pendingResyncScope)
    }
    return observed to requireNotNull(session.decode(base())).consumedGeneration
  }
}
