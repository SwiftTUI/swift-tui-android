package sh.swifttui.android.host

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/** A native virtual hierarchy gives TalkBack real accessibility focus and actions. */
@Composable
fun SwiftTUIAccessibilityOverlay(
  frame: SwiftTUIFrame?,
  style: SwiftTUIAndroidStyle,
  modifier: Modifier = Modifier,
  sendAction: (SwiftTUIAccessibilityActionRequest) -> Boolean = { false }
) {
  AndroidView(
    modifier = modifier,
    factory = { SwiftTUIAccessibilityView(it) },
    update = { it.present(frame, style, sendAction) }
  )
}

internal class SwiftTUIAccessibilityView(context: Context) : View(context) {
  private val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
  private var nodes = linkedMapOf<Int, SwiftTUIAccessibilityNode>()
  private var nextID = 0
  private var accessibilityFocus: Int? = null
  private var runtimeFocus: Int? = null
  private var hover: Int? = null
  private var lastSequence: Long? = null
  private var lastResponseID: String? = null
  private var style: SwiftTUIAndroidStyle? = null
  private var send: (SwiftTUIAccessibilityActionRequest) -> Boolean = { false }

  init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

  fun present(frame: SwiftTUIFrame?, style: SwiftTUIAndroidStyle,
    send: (SwiftTUIAccessibilityActionRequest) -> Boolean) {
    this.style = style
    this.send = send
    val previous = nodes
    val keys = previous.entries.associate { (id, node) -> (node.actionTarget ?: node.id) to id }
    val next = linkedMapOf<Int, SwiftTUIAccessibilityNode>()
    frame?.accessibilityNodes?.filter { !it.hidden && it.rect.width > 0 && it.rect.height > 0 }
      ?.forEach { node -> next[keys[node.actionTarget ?: node.id] ?: ++nextID] = node }
    if (accessibilityFocus != null && accessibilityFocus !in next) setFocus(null)
    if (hover != null && hover !in next) hover = null
    nodes = next
    val nextRuntimeFocus = nodes.entries.firstOrNull { it.value.isFocused }?.key
    val response = frame?.accessibilityActionResponse
    val rejected = response != null && response.requestID != lastResponseID && response.result != "accepted"
    if (response != null) lastResponseID = response.requestID
    val inputFocusChanged = nextRuntimeFocus != runtimeFocus
    runtimeFocus = nextRuntimeFocus
    if (inputFocusChanged || rejected) setFocus(nextRuntimeFocus)
    if (inputFocusChanged && nextRuntimeFocus != null) {
      event(nextRuntimeFocus, AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }
    if (previous.keys != next.keys) {
      event(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    } else {
      next.forEach { (id, node) ->
        // Focus has dedicated events. Invalidating the entire hierarchy on a
        // focus echo discards the screen reader's retained traversal position.
        if (node.copy(isFocused = false) != previous[id]?.copy(isFocused = false)) {
          event(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
      }
    }
    if (rejected) event(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    // Every occurrence in a new frame is an event, even identical messages.
    if (frame != null && frame.sequence != lastSequence) {
      frame.accessibilityAnnouncements.filter { it.message.isNotBlank() }.forEach {
        event(AccessibilityNodeProvider.HOST_VIEW_ID, AccessibilityEvent.TYPE_ANNOUNCEMENT, it.message)
      }
    }
    lastSequence = frame?.sequence
  }

  private fun bounds(node: SwiftTUIAccessibilityNode): Rect {
    val cell = style ?: return Rect()
    return Rect((node.rect.x * cell.cellWidthPx).roundToInt(), (node.rect.y * cell.cellHeightPx).roundToInt(),
      ((node.rect.x + node.rect.width) * cell.cellWidthPx).roundToInt(),
      ((node.rect.y + node.rect.height) * cell.cellHeightPx).roundToInt())
  }

  private fun event(id: Int, type: Int, message: String? = null) {
    if (!manager.isEnabled) return
    val event = AccessibilityEvent.obtain(type)
    event.packageName = context.packageName
    event.className = nodes[id]?.nativeClass() ?: "android.view.View"
    event.setSource(this, id)
    event.contentDescription = nodes[id]?.label
    event.isEnabled = nodes[id]?.isEnabled ?: true
    if (message != null) event.text.add(message)
    if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
      event.contentChangeTypes = if (id == AccessibilityNodeProvider.HOST_VIEW_ID)
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE else AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED
    }
    parent?.requestSendAccessibilityEvent(this, event)
  }

  private fun setFocus(id: Int?) {
    if (accessibilityFocus == id) return
    val previous = accessibilityFocus
    accessibilityFocus = id
    if (previous != null) event(previous, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
    if (id != null) event(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
  }

  private fun dispatch(id: Int, action: String, value: SwiftTUIAccessibilityValue? = null): Boolean {
    val node = nodes[id] ?: return false
    val target = node.actionTarget ?: return false
    if (!node.isEnabled || action !in node.actions) return false
    return send(SwiftTUIAccessibilityActionRequest(target, action, value))
  }

  private val provider = object : AccessibilityNodeProvider() {
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
      if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
        return AccessibilityNodeInfo.obtain(this@SwiftTUIAccessibilityView).also { info ->
          onInitializeAccessibilityNodeInfo(info)
          nodes.keys.forEach { info.addChild(this@SwiftTUIAccessibilityView, it) }
        }
      }
      val node = nodes[virtualViewId] ?: return null
      return AccessibilityNodeInfo.obtain().apply {
        setSource(this@SwiftTUIAccessibilityView, virtualViewId)
        setParent(this@SwiftTUIAccessibilityView)
        packageName = context.packageName
        className = node.nativeClass()
        contentDescription = node.label ?: node.role
        hintText = node.hint
        isEnabled = node.isEnabled
        isVisibleToUser = isShown
        isScreenReaderFocusable = true
        isAccessibilityFocused = accessibilityFocus == virtualViewId
        isFocused = node.isFocused
        isFocusable = "focus" in node.actions
        isPassword = node.role == "secureField"
        isEditable = "setValue" in node.actions && (node.role in setOf("textField", "secureField", "textEditor"))
        inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        if (!isPassword) text = (node.value as? SwiftTUIAccessibilityValue.TextValue)?.value
        (node.value as? SwiftTUIAccessibilityValue.BooleanValue)?.let {
          isCheckable = node.role in setOf("toggle", "checkbox")
          isChecked = isCheckable && it.value
          if (Build.VERSION.SDK_INT >= 30 && node.role == "disclosureGroup") {
            stateDescription = if (it.value) "Expanded" else "Collapsed"
          }
        }
        val number = (node.value as? SwiftTUIAccessibilityValue.NumberValue)?.value
        if (number != null && node.valueMin != null && node.valueMax != null &&
          number.toFloat().isFinite() && node.valueMin.toFloat().isFinite() && node.valueMax.toFloat().isFinite() &&
          node.valueMin <= node.valueMax) {
          rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            node.valueMin.toFloat(), node.valueMax.toFloat(), number.toFloat())
        }
        liveRegion = when (node.liveRegion) { "assertive" -> ACCESSIBILITY_LIVE_REGION_ASSERTIVE; "polite" -> ACCESSIBILITY_LIVE_REGION_POLITE; else -> ACCESSIBILITY_LIVE_REGION_NONE }
        val rect = bounds(node)
        setBoundsInParent(rect)
        val location = IntArray(2)
        getLocationOnScreen(location)
        rect.offset(location[0], location[1])
        setBoundsInScreen(rect)
        addAction(if (isAccessibilityFocused) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        if (node.isEnabled) {
          if ("focus" in node.actions) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
          if ("activate" in node.actions) { isClickable = true; addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) }
          if ("increment" in node.actions) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
          if ("decrement" in node.actions) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
          if ("setValue" in node.actions && number != null) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
          if (isEditable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        }
      }
    }

    override fun findFocus(focus: Int): AccessibilityNodeInfo? =
      (if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) accessibilityFocus else runtimeFocus)
        ?.let { createAccessibilityNodeInfo(it) }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
      val node = nodes[virtualViewId] ?: return false
      return when (action) {
        AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
          if (accessibilityFocus == virtualViewId) false
          else if (node.isEnabled && "focus" in node.actions && !dispatch(virtualViewId, "focus")) false
          else { setFocus(virtualViewId); true }
        }
        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
          if (accessibilityFocus != virtualViewId) false else { setFocus(null); true }
        }
        AccessibilityNodeInfo.ACTION_FOCUS -> dispatch(virtualViewId, "focus")
        AccessibilityNodeInfo.ACTION_CLICK -> dispatch(virtualViewId, "activate")
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> dispatch(virtualViewId, "increment")
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> dispatch(virtualViewId, "decrement")
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
          if (arguments?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) != true) false
          else dispatch(virtualViewId, "setValue", SwiftTUIAccessibilityValue.NumberValue(
            arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE).toDouble()))
        }
        AccessibilityNodeInfo.ACTION_SET_TEXT -> {
          val text = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
          if (text == null) false else dispatch(virtualViewId, "setValue", SwiftTUIAccessibilityValue.TextValue(text.toString()))
        }
        else -> false
      }
    }
  }

  override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider

  override fun dispatchHoverEvent(event: MotionEvent): Boolean {
    if (!manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
    val id = if (event.action == MotionEvent.ACTION_HOVER_EXIT) null else nodes.entries
      .lastOrNull { bounds(it.value).contains(event.x.toInt(), event.y.toInt()) }?.key
    if (id != hover) {
      if (id != null) event(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
      hover?.let { event(it, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) }
      hover = id
    }
    return id != null
  }
}

private fun SwiftTUIAccessibilityNode.nativeClass(): String = when (role) {
  "button", "menuItem", "disclosureGroup", "tab" -> "android.widget.Button"
  "checkbox" -> "android.widget.CheckBox"
  "toggle" -> "android.widget.Switch"
  "slider", "stepper" -> "android.widget.SeekBar"
  "textField", "secureField", "textEditor" -> "android.widget.EditText"
  "image" -> "android.widget.ImageView"
  else -> "android.view.View"
}
