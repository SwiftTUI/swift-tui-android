package sh.swifttui.android.host

import java.net.URLEncoder
import org.json.JSONObject

sealed interface SwiftTUIAccessibilityValue {
  data class BooleanValue(val value: Boolean) : SwiftTUIAccessibilityValue
  data class NumberValue(val value: Double) : SwiftTUIAccessibilityValue
  data class TextValue(val value: String) : SwiftTUIAccessibilityValue
}

data class SwiftTUIAccessibilityActionResponse(
  val requestID: String,
  val target: String,
  val result: String
)

data class SwiftTUIAccessibilityActionRequest(
  val target: String,
  val action: String,
  val value: SwiftTUIAccessibilityValue? = null
) {
  internal fun encode(requestID: Long): ByteArray? {
    if (target.isEmpty() || requestID <= 0) return null
    val suffix = when (action) {
      "focus", "activate", "increment", "decrement" -> if (value == null) "" else return null
      "setValue" -> when (value) {
        is SwiftTUIAccessibilityValue.BooleanValue -> ":boolean:${value.value}"
        is SwiftTUIAccessibilityValue.NumberValue -> {
          if (!value.value.isFinite()) return null
          ":number:${value.value}"
        }
        is SwiftTUIAccessibilityValue.TextValue -> ":text:${escape(value.value)}"
        null -> return null
      }
      else -> return null
    }
    val bytes = "\u001Eaccessibility:$requestID:${escape(target)}:$action$suffix\n".encodeToByteArray()
    return bytes.takeIf { it.size <= SwiftTUIWireBudget.RECORD_BYTES }
  }
}

private fun escape(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun JSONObject.toAccessibilityValue(): SwiftTUIAccessibilityValue? = when (opt("type")) {
  "boolean" -> (opt("value") as? Boolean)?.let(SwiftTUIAccessibilityValue::BooleanValue)
  "number" -> (opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() }
    ?.let(SwiftTUIAccessibilityValue::NumberValue)
  "text" -> (opt("value") as? String)?.let(SwiftTUIAccessibilityValue::TextValue)
  else -> null
}

internal fun JSONObject.toAccessibilityActionResponse(): SwiftTUIAccessibilityActionResponse? {
  val id = opt("requestID") as? String ?: return null
  val target = opt("target") as? String ?: return null
  val result = opt("result") as? String ?: return null
  if (id.toULongOrNull() == null || target.isEmpty() || result !in setOf(
    "accepted", "staleTarget", "disabled", "outOfScope", "unsupported", "invalidValue")) return null
  return SwiftTUIAccessibilityActionResponse(id, target, result)
}
