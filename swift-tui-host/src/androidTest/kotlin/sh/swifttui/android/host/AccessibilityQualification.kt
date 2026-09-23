package sh.swifttui.android.host

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo as Info

/** Native provider callbacks only; real Swift/TalkBack acceptance is a separate journey. */
internal fun qualifyAccessibility(context: Context): String {
  val view = SwiftTUIAccessibilityView(context)
  val style = SwiftTUIAndroidStyle(8f, 16f)
  val requests = mutableListOf<SwiftTUIAccessibilityActionRequest>()
  val send: (SwiftTUIAccessibilityActionRequest) -> Boolean = { requests.add(it); true }
  fun frame(sequence: Long, value: Int = 2, focused: Boolean = false, disabled: Boolean = false) =
    SwiftTUIWebSurfaceSession().decode(SwiftTUIWebSurfaceSession.RECORD_PREFIX + """{
      "version":2,"sequence":$sequence,"width":10,"height":1,"styles":[null],"rows":[[]],"images":[],
      "accessibilityTree":[{"id":"gain","rect":[0,0,5,1],"role":"slider","label":"Gain",
        "actionTarget":"gain-token","actions":["focus","increment","decrement","setValue"],
        "isEnabled":${!disabled},"isFocused":$focused,"value":{"type":"number","value":$value},"valueMin":0,"valueMax":10},
        {"id":"secret","rect":[5,0,5,1],"role":"secureField","label":"Password","actionTarget":"secret-token",
        "actions":["focus","setValue"],"value":{"type":"text","value":"must-be-redacted"}}]}
    """)!!
  view.present(frame(1), style, send)
  val provider = view.accessibilityNodeProvider
  check(provider.performAction(1, Info.ACTION_ACCESSIBILITY_FOCUS, null))
  check(requests.single().action == "focus")
  check(provider.findFocus(Info.FOCUS_ACCESSIBILITY)?.contentDescription == "Gain")
  view.present(frame(2, focused = true), style, send)
  check(requests.size == 1) { "runtime focus echoed" }
  check(provider.performAction(1, Info.ACTION_SCROLL_FORWARD, null))
  check(requests.last().action == "increment")
  check(provider.createAccessibilityNodeInfo(1)?.rangeInfo?.current == 2f) { "value changed optimistically" }
  view.present(frame(3, value = 3, focused = true), style, send)
  check(provider.createAccessibilityNodeInfo(1)?.rangeInfo?.current == 3f)
  check(provider.performAction(1, Info.AccessibilityAction.ACTION_SET_PROGRESS.id,
    Bundle().apply { putFloat(Info.ACTION_ARGUMENT_PROGRESS_VALUE, 7f) }))
  check(requests.last().value == SwiftTUIAccessibilityValue.NumberValue(7.0))
  check(provider.createAccessibilityNodeInfo(2)?.text == null)
  check(provider.createAccessibilityNodeInfo(2)?.isPassword == true)
  check(provider.performAction(2, Info.ACTION_SET_TEXT,
    Bundle().apply { putCharSequence(Info.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "private-input") }))
  check(requests.last().value == SwiftTUIAccessibilityValue.TextValue("private-input"))
  check(provider.createAccessibilityNodeInfo(2)?.text == null)
  view.present(frame(4, disabled = true), style, send)
  check(!provider.performAction(1, Info.ACTION_SCROLL_FORWARD, null))
  view.present(frame(5).copy(accessibilityNodes = emptyList()), style, send)
  check(provider.findFocus(Info.FOCUS_ACCESSIBILITY) == null)
  check(!provider.performAction(1, Info.ACTION_ACCESSIBILITY_FOCUS, null))
  check(!provider.performAction(1, Info.ACTION_SCROLL_FORWARD, null))
  return "ACCESSIBILITY-PROVIDER PASS focus actions authoritativeValue disabled removed secureRedaction noEcho"
}
