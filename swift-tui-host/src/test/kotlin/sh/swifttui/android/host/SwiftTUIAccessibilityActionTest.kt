package sh.swifttui.android.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SwiftTUIAccessibilityActionTest {
  @Test fun encodesTypedActionsWithoutFramingInjection() {
    val request = SwiftTUIAccessibilityActionRequest("scene:field/1", "setValue",
      SwiftTUIAccessibilityValue.TextValue("café:\n+"))
    assertEquals("\u001Eaccessibility:7:scene%3Afield%2F1:setValue:text:caf%C3%A9%3A%0A%2B\n",
      request.encode(7)?.decodeToString())
    assertNull(request.copy(action = "activate").encode(7))
    assertNull(request.copy(value = SwiftTUIAccessibilityValue.NumberValue(Double.NaN)).encode(7))
    assertNull(request.copy(target = "").encode(7))
    assertNull(request.encode(0))
    assertEquals("\u001Eaccessibility:1:x:increment\n", SwiftTUIAccessibilityActionRequest("x", "increment").encode(1)?.decodeToString())
  }

  @Test fun epochReplacementRetiresRepliesEvenWhenTargetsAreReused() {
    val requests = SwiftTUIAccessibilityRequests()
    requests.observeEpoch(null)
    var prior = requests.issue("same")!!
    for (epoch in listOf(7L, 8L, null)) {
      requests.observeEpoch(epoch)
      assertNull(requests.accept(SwiftTUIAccessibilityActionResponse(prior.toString(), "same", "accepted")))
      val current = requests.issue("same")!!
      assertTrue(current > prior)
      requests.observeEpoch(epoch)
      assertNotNull(requests.accept(SwiftTUIAccessibilityActionResponse(current.toString(), "same", "accepted")))
      prior = requests.issue("same")!!
    }
  }

  @Test fun decodesStrictValueTypesAndLosslessResponseIDs() {
    assertNull(JSONObject("""{"type":"boolean","value":"true"}""").toAccessibilityValue())
    assertNull(JSONObject("""{"type":"number","value":"3"}""").toAccessibilityValue())
    assertEquals(SwiftTUIAccessibilityValue.NumberValue(3.0), JSONObject("""{"type":"number","value":3}""").toAccessibilityValue())
    assertEquals("18446744073709551615", JSONObject("""{"requestID":"18446744073709551615","target":"x","result":"disabled"}""").toAccessibilityActionResponse()?.requestID)
    assertNull(JSONObject("""{"requestID":7,"target":"x","result":"accepted"}""").toAccessibilityActionResponse())
  }

  @Test fun responseCorrelationRejectsUnissuedMismatchedExpiredAndPriorSessionIDs() {
    var now = 0L
    val requests = SwiftTUIAccessibilityRequests { now }
    val first = requests.issue("a")!!
    val second = requests.issue("b")!!
    fun response(id: String, target: String) = SwiftTUIAccessibilityActionResponse(id, target, "accepted")
    for (id in listOf("18446744073709551615", "18446744073709551616", "+1", "", "１")) {
      assertNull(requests.accept(response(id, "a")))
    }
    assertNull(requests.accept(response(first.toString(), "b")))
    assertNotNull(requests.accept(response(second.toString(), "b")))
    assertNull(requests.accept(response(second.toString(), "b")))
    assertNotNull(requests.accept(response("000$first", "a")))
    val prior = requests.issue("a")!!
    requests.reset()
    val current = requests.issue("a")!!
    assertNotEquals(prior, current)
    assertNull(requests.accept(response(prior.toString(), "a")))
    now = 30_000
    assertNull(requests.accept(response(current.toString(), "a")))
    val oldest = requests.issue("a")!!
    repeat(256) { requests.issue("a") }
    assertNull(requests.accept(response(oldest.toString(), "a")))
    val gone = requests.issue("gone")!!
    requests.retainTargets(setOf("a"))
    assertNull(requests.accept(response(gone.toString(), "gone")))
  }

  @Test fun optionalInvalidReplyDoesNotDiscardAFrame() {
    for (id in listOf("18446744073709551616", "+1", "", "１")) {
      val frame = SwiftTUIWebSurfaceSession().decode("\u001Esurface:" +
        """{"version":2,"width":1,"height":1,"styles":[null],"rows":[[[0,"x",1,0]]],"accessibilityActionResponse":{"requestID":"$id","target":"x","result":"accepted"}}""")!!
      assertNull(frame.accessibilityActionResponse)
      assertEquals("x", frame.cells.single().character)
    }
  }

  @Test fun sharedFixtureDecodesActionsAndSecureFieldsNeverExposeWireValues() {
    val fixture = javaClass.getResourceAsStream("/web-surface-totality.txt")!!
      .bufferedReader().readText().replace("\\u001E", "\u001E")
    val frame = SwiftTUIWebSurfaceSession().decode(fixture)!!
    val node = frame.accessibilityNodes.single()
    assertEquals("fixture-token", node.actionTarget)
    assertEquals(setOf("focus", "setValue", "custom"), node.actions)
    assertFalse(node.isEnabled)
    assertEquals(SwiftTUIAccessibilityValue.TextValue("Current"), node.value)
    assertEquals(0.0, node.valueMin!!, 0.0)
    assertEquals(10.0, node.valueMax!!, 0.0)
    assertEquals("accepted", frame.accessibilityActionResponse?.result)
    val secure = SwiftTUIWebSurfaceSession().decode(fixture.replace("\"role\":\"textField\"", "\"role\":\"secureField\""))!!
    assertNull(secure.accessibilityNodes.single().value)
  }
}
