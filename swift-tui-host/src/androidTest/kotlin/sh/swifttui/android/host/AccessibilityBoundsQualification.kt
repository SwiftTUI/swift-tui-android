package sh.swifttui.android.host

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One frame, presented once: every subsequent change is native view movement. */
class AccessibilityBoundsActivity : Activity() {
  internal lateinit var overlay: SwiftTUIAccessibilityView
  internal lateinit var container: FrameLayout

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    container = FrameLayout(this).apply {
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    overlay = SwiftTUIAccessibilityView(this)
    container.addView(overlay, FrameLayout.LayoutParams(400, 200).apply {
      leftMargin = 80
      topMargin = 100
    })
    setContentView(container)
    val frame = SwiftTUIWebSurfaceSession().decode(SwiftTUIWebSurfaceSession.RECORD_PREFIX + """{
      "version":2,"sequence":1,"width":10,"height":2,"styles":[null],"rows":[[],[]],"images":[],
      "accessibilityTree":[{"id":"bounds","rect":[1,1,5,1],"role":"button","label":"Moving bounds",
        "actionTarget":"bounds-token","actions":["activate"]}]}
    """)!!
    overlay.present(frame, SwiftTUIAndroidStyle(20f, 30f)) { error("Movement must not send runtime actions") }
  }
}

/** Exercise Android's service-side node cache, not direct provider callbacks. */
internal fun Instrumentation.qualifyAccessibilityBounds(): String {
  val automation = uiAutomation // Enable accessibility before attaching the view.
  val activity = startActivitySync(Intent(context, AccessibilityBoundsActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }) as AccessibilityBoundsActivity

  fun findNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
    if (node == null) return null
    if (node.contentDescription == "Moving bounds") return node
    for (index in 0 until node.childCount) findNode(node.getChild(index))?.let { return it }
    return null
  }
  fun screenBounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }
  fun expectedBounds(): Rect {
    val location = IntArray(2)
    runOnMainSync { activity.overlay.getLocationOnScreen(location) }
    return Rect(20, 30, 120, 60).apply { offset(location[0], location[1]) }
  }
  fun awaitBounds(description: String, read: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
    val deadline = SystemClock.uptimeMillis() + 5000
    var actual: Rect? = null
    do {
      val node = read()
      actual = node?.let(::screenBounds)
      if (node != null && actual == expectedBounds()) return node
      SystemClock.sleep(25)
    } while (SystemClock.uptimeMillis() < deadline)
    error("$description: expected ${expectedBounds()}, cached bounds $actual")
  }

  try {
    waitForIdleSync()
    automation.waitForIdle(200, 5000)
    val initial = awaitBounds("initial bounds") { findNode(automation.rootInActiveWindow) }
    val host = requireNotNull(initial.parent)
    check(host.childCount == 1)
    // Warm the cache through a retained host. Reading its child again must use
    // normal framework invalidation; never refresh nodes or clear the cache.
    check(screenBounds(requireNotNull(host.getChild(0))) == screenBounds(initial))

    fun move(description: String, change: () -> Unit) {
      val before = expectedBounds()
      runOnMainSync(change)
      waitForIdleSync()
      check(expectedBounds() != before) { "$description did not move the host" }
      awaitBounds(description) { host.getChild(0) }
    }
    fun animateAncestor(description: String) {
      // ViewPropertyAnimator moves the ancestor without the subtree notification
      // that View.translationX/Y setters send themselves, exposing the stale cache.
      val before = expectedBounds()
      val animated = CountDownLatch(1)
      runOnMainSync {
        activity.container.animate()
          .translationX(activity.container.translationX + 13f)
          .translationY(activity.container.translationY + 41f)
          .setDuration(250).withEndAction { animated.countDown() }.start()
      }
      check(animated.await(5, TimeUnit.SECONDS)) { "$description did not finish" }
      check(expectedBounds() != before) { "$description did not move the host" }
      awaitBounds(description) { host.getChild(0) }
    }
    animateAncestor("ancestor animation")
    move("host translation") {
      activity.overlay.translationX = 37f
      activity.overlay.translationY = 53f
    }
    move("ancestor translation") { activity.container.translationY = 29f }
    move("ancestor scroll") { activity.container.scrollTo(11, 17) }
    move("layout offset") {
      activity.overlay.layoutParams = (activity.overlay.layoutParams as FrameLayout.LayoutParams).apply {
        leftMargin += 19
        topMargin += 23
      }
    }
    runOnMainSync {
      activity.container.removeView(activity.overlay)
      activity.container.addView(activity.overlay)
    }
    waitForIdleSync()
    awaitBounds("reattached bounds") { findNode(automation.rootInActiveWindow) }
    animateAncestor("animation after reattach")
    return "ACCESSIBILITY-BOUNDS PASS ancestorAnimation hostTranslation ancestorTranslation ancestorScroll layout reattach noNewFrame noRefresh"
  } finally {
    runOnMainSync { activity.finish() }
  }
}
