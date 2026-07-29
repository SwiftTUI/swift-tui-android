package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTUIFramePollerTest {
  @Test
  fun droppedDeltaRequestsOneKeyframeAndTheNextFullFrameRecovers() {
    val native = FakeFrameNative()
    val poller = native.makePoller()

    native.enqueue(fullRecord(sequence = 1, epoch = 71, generation = 1, text = "A"))
    assertTrue(poller.poll(9) is SwiftTUIFramePollResult.Frame)

    // Producer generation 2 is deliberately dropped. Generation 3 cannot be
    // applied over the consumer's generation-1 baseline.
    native.enqueue(
      deltaRecord(
        sequence = 3,
        epoch = 71,
        generation = 3,
        baselineGeneration = 2,
        text = "C"
      )
    )
    assertEquals(SwiftTUIFramePollResult.None, poller.poll(9))
    assertEquals(listOf("""{"scope":"keyframe"}"""), native.resyncRequests)

    // Repeated bad deltas do not flood the uplink while repair is outstanding.
    native.enqueue(
      deltaRecord(
        sequence = 4,
        epoch = 71,
        generation = 4,
        baselineGeneration = 3,
        text = "D"
      )
    )
    assertEquals(SwiftTUIFramePollResult.None, poller.poll(9))
    assertEquals(1, native.resyncRequests.size)

    native.enqueue(fullRecord(sequence = 4, epoch = 71, generation = 4, text = "K"))
    val keyframe = poller.poll(9)
    assertTrue(keyframe is SwiftTUIFramePollResult.Frame)
    assertEquals(
      "K",
      (keyframe as SwiftTUIFramePollResult.Frame).value.cells.single().character
    )

    native.enqueue(
      deltaRecord(
        sequence = 5,
        epoch = 71,
        generation = 5,
        baselineGeneration = 4,
        text = "R"
      )
    )
    val recovered = poller.poll(9)
    assertTrue(recovered is SwiftTUIFramePollResult.Frame)
    assertEquals(
      "R",
      (recovered as SwiftTUIFramePollResult.Frame).value.cells.single().character
    )
    assertEquals(1, native.resyncRequests.size)
  }

  @Test
  fun missingOldHostSymbolFallsBackToWaitingForAnIncidentalKeyframe() {
    val native = FakeFrameNative(resyncResult = 0)
    val poller = native.makePoller()

    native.enqueue(fullRecord(sequence = 1, epoch = 81, generation = 1, text = "A"))
    poller.poll(3)
    native.enqueue(
      deltaRecord(
        sequence = 3,
        epoch = 81,
        generation = 3,
        baselineGeneration = 2,
        text = "C"
      )
    )
    poller.poll(3)
    native.enqueue(
      deltaRecord(
        sequence = 4,
        epoch = 81,
        generation = 4,
        baselineGeneration = 3,
        text = "D"
      )
    )
    poller.poll(3)

    // Zero is what the lazy JNI binding returns when an older host library
    // lacks swift_tui_android_request_resync. It is still deduplicated.
    assertEquals(1, native.resyncRequests.size)

    native.enqueue(fullRecord(sequence = 5, epoch = 81, generation = 5, text = "K"))
    assertTrue(poller.poll(3) is SwiftTUIFramePollResult.Frame)

    // A full frame clears the outstanding request, so a later independent
    // mismatch may request repair again.
    native.enqueue(
      deltaRecord(
        sequence = 7,
        epoch = 81,
        generation = 7,
        baselineGeneration = 6,
        text = "Z"
      )
    )
    poller.poll(3)
    assertEquals(2, native.resyncRequests.size)
  }

  @Test
  fun malformedFrameFailureAlsoRequestsOneKeyframe() {
    val native = FakeFrameNative()
    val poller = native.makePoller()

    native.enqueue(SwiftTUIWebSurfaceSession.RECORD_PREFIX + "{\n")
    val failure = poller.poll(4)

    assertTrue(failure is SwiftTUIFramePollResult.Error)
    assertEquals(listOf("""{"scope":"keyframe"}"""), native.resyncRequests)
  }

  @Test
  fun repeatedImageMissesAreSortedCoalescedAndDispatchedWithoutANewFrame() {
    val native = FakeFrameNative()
    val poller = native.makePoller()

    poller.reportMissingImagePayloads(listOf("zeta", "alpha", "zeta"))
    assertEquals(SwiftTUIFramePollResult.None, poller.poll(5))
    assertEquals(
      listOf("""{"scope":"images","ids":["alpha","zeta"]}"""),
      native.resyncRequests
    )

    poller.reportMissingImagePayloads(listOf("alpha", "zeta"))
    assertEquals(SwiftTUIFramePollResult.None, poller.poll(5))
    assertEquals(1, native.resyncRequests.size)
  }

  @Test
  fun payloadArrivalClearsOnlyItsMatchingOutstandingImage() {
    val native = FakeFrameNative()
    val poller = native.makePoller()

    poller.reportMissingImagePayloads(listOf("alpha", "beta"))
    poller.poll(6)

    native.enqueue(
      fullRecord(
        sequence = 1,
        epoch = 91,
        generation = 1,
        text = "A",
        imagesJson = imageJson(id = "alpha", payload = "payload-alpha")
      )
    )
    assertTrue(poller.poll(6) is SwiftTUIFramePollResult.Frame)

    poller.reportMissingImagePayloads(listOf("alpha", "beta"))
    poller.poll(6)
    assertEquals(
      listOf(
        """{"scope":"images","ids":["alpha","beta"]}""",
        """{"scope":"images","ids":["alpha"]}"""
      ),
      native.resyncRequests
    )
  }

  @Test
  fun imageRequestsDedupeOldHostZeroAndResetWithTheEpoch() {
    val native = FakeFrameNative(resyncResult = 0)
    val poller = native.makePoller()

    poller.reportMissingImagePayloads(listOf("hero"))
    poller.poll(7)
    poller.reportMissingImagePayloads(listOf("hero"))
    poller.poll(7)
    assertEquals(1, native.resyncRequests.size)

    poller.reset()
    poller.reportMissingImagePayloads(listOf("hero"))
    poller.poll(7)
    assertEquals(2, native.resyncRequests.size)
  }

  @Test
  fun keyframeAndImageRecoveryRequestsCoexist() {
    val native = FakeFrameNative()
    val poller = native.makePoller()

    native.enqueue(fullRecord(sequence = 1, epoch = 101, generation = 1, text = "A"))
    poller.poll(8)
    poller.reportMissingImagePayloads(listOf("hero"))
    native.enqueue(
      deltaRecord(
        sequence = 3,
        epoch = 101,
        generation = 3,
        baselineGeneration = 2,
        text = "C"
      )
    )

    assertEquals(SwiftTUIFramePollResult.None, poller.poll(8))
    assertEquals(
      listOf(
        """{"scope":"keyframe"}""",
        """{"scope":"images","ids":["hero"]}"""
      ),
      native.resyncRequests
    )
  }

  private class FakeFrameNative(
    private val resyncResult: Int = 1
  ) {
    private val records = ArrayDeque<ByteArray>()
    val resyncRequests = mutableListOf<String>()

    fun enqueue(record: String) {
      records.addLast(record.encodeToByteArray())
    }

    fun makePoller(): SwiftTUIFramePoller =
      SwiftTUIFramePoller(
        session = SwiftTUIWebSurfaceSession(),
        copyLatestFrame = ::copyLatestFrame,
        requestResync = ::requestResync
      )

    private fun copyLatestFrame(
      @Suppress("UNUSED_PARAMETER") handle: Long,
      destination: ByteArray?,
      capacity: Int
    ): Int {
      val record = records.firstOrNull() ?: return 0
      if (destination == null || capacity <= 0) {
        return record.size
      }
      if (capacity < record.size) {
        return record.size
      }
      record.copyInto(destination)
      records.removeFirst()
      return record.size
    }

    private fun requestResync(
      @Suppress("UNUSED_PARAMETER") handle: Long,
      json: ByteArray,
      count: Int
    ): Int {
      resyncRequests += json.decodeToString(0, count)
      return resyncResult
    }
  }

  private companion object {
    private val prefix = SwiftTUIWebSurfaceSession.RECORD_PREFIX

    fun fullRecord(
      sequence: Long,
      epoch: Long,
      generation: Long,
      text: String,
      imagesJson: String = ""
    ): String =
      prefix +
        """{"version":2,"epoch":$epoch,"gen":$generation,"sequence":$sequence,""" +
        """"width":1,"height":1,"styles":[null],""" +
        """"rows":[[[0,"$text",1,0]]],"images":[$imagesJson]}""" + "\n"

    fun imageJson(id: String, payload: String?): String {
      val payloadField = payload?.let { ""","dataBase64":"$it"""" }.orEmpty()
      return """{"id":"$id","bounds":[0,0,1,1],"visibleBounds":[0,0,1,1],""" +
        """"pixelSize":[1,1],"format":"png","scalingMode":"stretch"$payloadField}"""
    }

    fun deltaRecord(
      sequence: Long,
      epoch: Long,
      generation: Long,
      baselineGeneration: Long,
      text: String
    ): String =
      prefix +
        """{"version":3,"encoding":"delta","epoch":$epoch,"gen":$generation,""" +
        """"baselineGen":$baselineGeneration,"sequence":$sequence,"width":1,""" +
        """"height":1,"styles":[null],"deltaRows":[[0,[[0,"$text",1,0]]]],""" +
        """"images":[]}""" + "\n"
  }
}
