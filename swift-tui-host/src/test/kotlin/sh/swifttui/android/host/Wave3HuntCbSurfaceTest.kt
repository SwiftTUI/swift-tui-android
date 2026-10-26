package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Wave-3 regression hunt, part CB (STUI-1167): neighbours of the STUI-1145
 * strict-JSON lexical scan. The coordination root's wave-3 hunt evidence owns
 * the case descriptions (w3cb011 to w3cb015).
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
    val observed = accepted(trailing)
    knownIssue(
      "w3cb011 NEW-4: a surface record ending in a trailing member separator is committed; " +
        "$MECHANISM accepts ',}' (SwiftTUIWebSurfaceSession.kt:84); contract: README.md:17-21, RFC 8259 §4; adjacent STUI-1145",
      observed = { assertEquals(listOf(true) to 3L, observed) },
      contract = { assertEquals(listOf(false) to 2L, observed) }
    )
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
    val observed = listOf(accepted(elided), accepted(trailing))
    knownIssue(
      "w3cb012 NEW-4: surface records with an elided or a trailing array separator are committed; " +
        "$MECHANISM reads '[,x]' as [null,x] and drops ',]' (SwiftTUIWebSurfaceSession.kt:84); contract: README.md:17-21, RFC 8259 §5; adjacent STUI-1145",
      observed = { assertEquals(listOf(listOf(true) to 3L, listOf(true) to 3L), observed) },
      contract = { assertEquals(listOf(listOf(false) to 2L, listOf(false) to 2L), observed) }
    )
  }

  @Test
  fun w3cb013ContentAfterTheSurfaceObjectIsRefused() {
    val frame =
      """{"version":2,"epoch":7,"gen":2,"width":1,"height":2,"styles":[null],"rows":[[[0,"b",1,0]],[]]}"""
    val second =
      """{"version":2,"epoch":7,"gen":3,"width":1,"height":2,"styles":[null],"rows":[[[0,"z",1,0]],[]]}"""
    val observed = listOf(accepted(surface(frame + second)), accepted(surface("$frame []")))
    knownIssue(
      "w3cb013 NEW-4: a surface record carrying a value after its object commits the first object; " +
        "$MECHANISM's JSONObject(String) stops after the first value (SwiftTUIWebSurfaceSession.kt:84); contract: README.md:17-21, RFC 8259 §2; adjacent STUI-1145",
      observed = { assertEquals(listOf(listOf(true) to 3L, listOf(true) to 3L), observed) },
      contract = { assertEquals(listOf(listOf(false) to 2L, listOf(false) to 2L), observed) }
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

  /**
   * A reproduced finding asserts the observed value, requires the contract
   * expectation to fail, and logs a W3HUNT_KNOWN_ASSERTION line citing the
   * proposed issue. With SWIFTTUI_W3HUNT_UNMARKED=1 only the contract runs.
   */
  private fun knownIssue(message: String, observed: () -> Unit, contract: () -> Unit) {
    if (System.getenv("SWIFTTUI_W3HUNT_UNMARKED") == "1") {
      contract()
      return
    }
    observed()
    assertThrows(AssertionError::class.java) { contract() }
    println("W3HUNT_KNOWN_ASSERTION $message")
  }

  private data class Admission(val accepted: Boolean, val text: String?, val resync: String?)

  private companion object {
    const val MECHANISM =
      "the lexical scan validates bare runs and nesting, not the RFC 8259 value grammar (SwiftTUIWireBudget.kt:55-87), and org.json"
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

  private fun accepted(vararg records: String): Pair<List<Boolean>, Long> {
    val (observed, generation) = admissions(*records)
    return observed.map { it.accepted } to generation
  }
}
