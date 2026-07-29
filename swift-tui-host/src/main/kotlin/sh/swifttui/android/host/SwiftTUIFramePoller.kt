package sh.swifttui.android.host

internal sealed interface SwiftTUIFramePollResult {
  data object None : SwiftTUIFramePollResult
  data class Frame(val value: SwiftTUIFrame) : SwiftTUIFramePollResult
  data class Error(val message: String) : SwiftTUIFramePollResult
}

/**
 * Owns the delivery-repair state for the Android size/copy poll seam.
 *
 * A rejected record schedules one keyframe request. The request stays
 * outstanding even when an older Swift host does not expose the resync
 * symbol, so the Kotlin host waits for an incidental full frame instead of
 * flooding the bridge on every poll.
 */
internal class SwiftTUIFramePoller(
  private val session: SwiftTUIWebSurfaceSession,
  private val copyLatestFrame: (Long, ByteArray?, Int) -> Int,
  private val requestResync: (Long, ByteArray, Int) -> Int
) {
  private var keyframeRequestOutstanding = false

  fun reset() {
    session.reset()
    keyframeRequestOutstanding = false
  }

  fun poll(handle: Long): SwiftTUIFramePollResult {
    val needed = copyLatestFrame(handle, null, 0)
    if (needed <= 0) {
      return SwiftTUIFramePollResult.None
    }

    val bytes = ByteArray(needed)
    val copied = copyLatestFrame(handle, bytes, bytes.size)
    if (copied <= 0 || copied > bytes.size) {
      return SwiftTUIFramePollResult.None
    }

    val payload = bytes.decodeToString(0, copied)
    val decoded = runCatching {
      require(SwiftTUIWebSurfaceSession.isWebSurfaceRecord(payload)) {
        "legacy SwiftTUI frame received; the app's swift-tui host library " +
          "predates the converged web-surface wire — update the swift-tui " +
          "dependency to match this AAR."
      }
      session.decode(payload)
    }

    val error = decoded.exceptionOrNull()
    if (error != null) {
      session.requestKeyframeRecovery()
      dispatchPendingResync(handle)
      return SwiftTUIFramePollResult.Error(error.message ?: error.toString())
    }

    val frame = decoded.getOrNull()
    if (frame == null) {
      dispatchPendingResync(handle)
      return SwiftTUIFramePollResult.None
    }

    if (session.pendingResyncScope == null) {
      keyframeRequestOutstanding = false
    } else {
      dispatchPendingResync(handle)
    }
    return SwiftTUIFramePollResult.Frame(frame)
  }

  private fun dispatchPendingResync(handle: Long) {
    if (
      session.pendingResyncScope != SwiftTUIWebSurfaceSession.KEYFRAME_RESYNC_SCOPE ||
      keyframeRequestOutstanding
    ) {
      return
    }
    val request = KEYFRAME_RESYNC_JSON.encodeToByteArray()
    // Mark outstanding regardless of the return value. A zero result is the
    // old-host fallback: wait for an incidental full frame.
    keyframeRequestOutstanding = true
    requestResync(handle, request, request.size)
  }

  private companion object {
    const val KEYFRAME_RESYNC_JSON = """{"scope":"keyframe"}"""
  }
}
