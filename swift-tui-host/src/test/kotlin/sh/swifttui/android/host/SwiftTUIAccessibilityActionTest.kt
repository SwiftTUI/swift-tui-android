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

  @Test fun decodesStrictValueTypesAndLosslessResponseIDs() {
    assertNull(JSONObject("""{"type":"boolean","value":"true"}""").toAccessibilityValue())
    assertNull(JSONObject("""{"type":"number","value":"3"}""").toAccessibilityValue())
    assertEquals(SwiftTUIAccessibilityValue.NumberValue(3.0), JSONObject("""{"type":"number","value":3}""").toAccessibilityValue())
    assertEquals("18446744073709551615", JSONObject("""{"requestID":"18446744073709551615","target":"x","result":"disabled"}""").toAccessibilityActionResponse()?.requestID)
    assertNull(JSONObject("""{"requestID":7,"target":"x","result":"accepted"}""").toAccessibilityActionResponse())
  }

  @Test fun sharedFixtureDecodesActionsAndSecureFieldsNeverExposeWireValues() {
    val fixture = javaClass.getResourceAsStream("/web-surface-totality.txt")!!
      .bufferedReader().readText().replace("\\u001E", "\u001E")
    val frame = SwiftTUIWebSurfaceSession().decode(fixture)!!
    val node = frame.accessibilityNodes.single()
    assertEquals("fixture-token", node.actionTarget)
    assertEquals(setOf("focus", "setValue"), node.actions)
    assertFalse(node.isEnabled)
    assertEquals(SwiftTUIAccessibilityValue.TextValue("Current"), node.value)
    assertEquals(0.0, node.valueMin!!, 0.0)
    assertEquals(10.0, node.valueMax!!, 0.0)
    assertEquals("accepted", frame.accessibilityActionResponse?.result)
    val secure = SwiftTUIWebSurfaceSession().decode(fixture.replace("\"role\":\"textField\"", "\"role\":\"secureField\""))!!
    assertNull(secure.accessibilityNodes.single().value)
  }
}
