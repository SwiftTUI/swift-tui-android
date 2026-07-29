package sh.swifttui.android.host

import org.json.JSONObject

internal sealed interface SwiftTUIFramePollResult {
  data object None : SwiftTUIFramePollResult
  data class Frame(
    val value: SwiftTUIFrame,
    /**
     * A requested payload may be re-encoded from the latest application frame,
     * so its application [SwiftTUIFrame.sequence] legitimately stays equal to
     * the frame currently published by host state. This signal bypasses only
     * that duplicate-sequence suppression.
     */
    val isImagePayloadRepair: Boolean
  ) : SwiftTUIFramePollResult {
    fun shouldPublishOver(current: SwiftTUIFrame?): Boolean =
      isImagePayloadRepair || value.sequence != current?.sequence
  }
  data class Error(val message: String) : SwiftTUIFramePollResult
}

/**
 * Owns the delivery-repair state for the Android size/copy poll seam.
 *
 * A rejected record schedules one keyframe request. The request stays
 * outstanding even when an older Swift host does not expose the resync
 * symbol, so the Kotlin host waits for an incidental full frame instead of
 * flooding the bridge on every poll. Image repair is independent: missing IDs
 * are coalesced into one sorted request and each remains outstanding until a
 * payload-bearing record for that exact ID arrives.
 */
internal class SwiftTUIFramePoller(
  private val session: SwiftTUIWebSurfaceSession,
  private val copyLatestFrame: (Long, ByteArray?, Int) -> Int,
  private val requestResync: (Long, ByteArray, Int) -> Int
) {
  private var keyframeRequestOutstanding = false
  private val queuedImagePayloadIds = linkedSetOf<String>()
  private val outstandingImagePayloadIds = mutableSetOf<String>()

  fun reset() {
    session.reset()
    keyframeRequestOutstanding = false
    queuedImagePayloadIds.clear()
    outstandingImagePayloadIds.clear()
  }

  fun reportMissingImagePayloads(ids: Iterable<String>) {
    for (id in ids) {
      if (id !in outstandingImagePayloadIds) {
        queuedImagePayloadIds.add(id)
      }
    }
  }

  fun poll(handle: Long): SwiftTUIFramePollResult {
    val needed = copyLatestFrame(handle, null, 0)
    if (needed <= 0) {
      dispatchPendingResync(handle)
      return SwiftTUIFramePollResult.None
    }

    val bytes = ByteArray(needed)
    val copied = copyLatestFrame(handle, bytes, bytes.size)
    if (copied <= 0 || copied > bytes.size) {
      dispatchPendingResync(handle)
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

    var isImagePayloadRepair = false
    for (attachment in frame.imageAttachments) {
      if (attachment.payloadBase64 != null) {
        if (
          attachment.id in queuedImagePayloadIds ||
          attachment.id in outstandingImagePayloadIds
        ) {
          isImagePayloadRepair = true
        }
        queuedImagePayloadIds.remove(attachment.id)
        outstandingImagePayloadIds.remove(attachment.id)
      }
    }

    if (session.pendingResyncScope == null) {
      keyframeRequestOutstanding = false
    }
    dispatchPendingResync(handle)
    return SwiftTUIFramePollResult.Frame(
      value = frame,
      isImagePayloadRepair = isImagePayloadRepair
    )
  }

  private fun dispatchPendingResync(handle: Long) {
    if (
      session.pendingResyncScope == SwiftTUIWebSurfaceSession.KEYFRAME_RESYNC_SCOPE &&
      !keyframeRequestOutstanding
    ) {
      val request = KEYFRAME_RESYNC_JSON.encodeToByteArray()
      // Mark outstanding regardless of the return value. A zero result is the
      // old-host fallback: wait for an incidental full frame.
      keyframeRequestOutstanding = true
      requestResync(handle, request, request.size)
    }

    if (queuedImagePayloadIds.isEmpty()) {
      return
    }
    val ids = queuedImagePayloadIds.sorted()
    queuedImagePayloadIds.clear()
    outstandingImagePayloadIds.addAll(ids)
    val json = ids.joinToString(
      separator = ",",
      prefix = """{"scope":"images","ids":[""",
      postfix = "]}"
    ) { id ->
      JSONObject.quote(id)
    }
    val request = json.encodeToByteArray()
    // As with keyframes, zero means an older host lacks the lazy symbol.
    // Keeping the IDs outstanding prevents a 30 Hz request loop.
    requestResync(handle, request, request.size)
  }

  private companion object {
    const val KEYFRAME_RESYNC_JSON = """{"scope":"keyframe"}"""
  }
}
