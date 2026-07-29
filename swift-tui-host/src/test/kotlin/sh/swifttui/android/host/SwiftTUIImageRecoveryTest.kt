package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

private const val IMAGE_MIB = 1024 * 1024

private data class ModelBitmap(
  val size: Int,
  var recycled: Boolean = false
)

private class ModelBitmapCache(
  val maxSize: Int
) {
  private val entries = LinkedHashMap<String, ModelBitmap>(16, 0.75f, true)
  private var size = 0

  var recycledReturnCount = 0
    private set

  fun get(id: String): ModelBitmap? = entries[id]

  fun put(id: String, bitmap: ModelBitmap) {
    entries.put(id, bitmap)?.let { replaced ->
      size -= replaced.size
      replaced.recycled = true
    }
    size += bitmap.size
    while (size > maxSize) {
      val eldest = entries.entries.first()
      entries.remove(eldest.key)
      size -= eldest.value.size
      eldest.value.recycled = true
    }
    if (bitmap.recycled) {
      recycledReturnCount += 1
    }
  }

  fun evict(id: String) {
    entries.remove(id)?.let { removed ->
      size -= removed.size
      removed.recycled = true
    }
  }
}

private class ModelRenderer(
  cacheBudget: Int
) {
  private val cache = ModelBitmapCache(cacheBudget)
  private val missingPayloadIds = linkedSetOf<String>()

  val recycledReturnCount: Int
    get() = cache.recycledReturnCount

  fun render(attachment: SwiftTUIImageAttachment): Boolean {
    val resolution = SwiftTUIBitmapCachePolicy.resolve(
      key = attachment.id,
      cached = { cache.get(attachment.id) },
      payload = attachment.payloadBase64,
      decode = { payload ->
        if (payload.startsWith("encoded-")) ModelBitmap(IMAGE_MIB) else null
      },
      maxSize = { cache.maxSize },
      sizeOf = { it.size },
      put = cache::put
    )
    val drawable = when (resolution) {
      is SwiftTUIBitmapResolution.Drawable -> !resolution.value.recycled
      SwiftTUIBitmapResolution.MissingPayload -> {
        missingPayloadIds.add(attachment.id)
        false
      }
      SwiftTUIBitmapResolution.InvalidPayload -> false
    }
    return drawable
  }

  fun drainMissingPayloadIds(): Set<String> =
    missingPayloadIds.toSet().also { missingPayloadIds.clear() }

  fun evict(id: String) {
    cache.evict(id)
  }
}

private class ImageRecoveryNative(
  private val resyncResult: Int = 1
) {
  private val records = ArrayDeque<ByteArray>()
  val resyncRequests = mutableListOf<String>()

  val poller = SwiftTUIFramePoller(
    session = SwiftTUIWebSurfaceSession(),
    copyLatestFrame = ::copyLatestFrame,
    requestResync = ::requestResync
  )

  fun enqueue(record: String) {
    records.addLast(record.encodeToByteArray())
  }

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

private fun ImageRecoveryNative.pollFrame(): SwiftTUIFrame {
  return pollFrameResult().value
}

private fun ImageRecoveryNative.pollFrameResult(): SwiftTUIFramePollResult.Frame {
  val result = poller.poll(handle = 17)
  assertTrue(result is SwiftTUIFramePollResult.Frame)
  return result as SwiftTUIFramePollResult.Frame
}

private fun imageRecord(
  sequence: Int,
  generation: Int = sequence,
  imageID: String,
  carriesPayload: Boolean
): String {
  val payload = if (carriesPayload) ""","dataBase64":"encoded-$imageID"""" else ""
  return SwiftTUIWebSurfaceSession.RECORD_PREFIX +
    """{"version":2,"epoch":301,"gen":$generation,"sequence":$sequence,""" +
    """"width":1,"height":1,"styles":[null],"rows":[[[0," ",1,0]]],""" +
    """"images":[{"id":"$imageID","bounds":[0,0,1,1],""" +
    """"visibleBounds":[0,0,1,1],"pixelSize":[512,512],"format":"png",""" +
    """"scalingMode":"stretch"$payload}]}""" + "\n"
}

class SwiftTUIImageRecoveryTest {
  @Test
  fun insertEvictMissRequestsOnceAndSameSequencePayloadRecoveryReapplies() {
    val native = ImageRecoveryNative()
    val renderer = ModelRenderer(cacheBudget = 8 * IMAGE_MIB)

    native.enqueue(imageRecord(sequence = 1, imageID = "hero", carriesPayload = true))
    val inserted = native.pollFrame().imageAttachments.single()
    assertTrue(renderer.render(inserted))

    renderer.evict("hero")
    native.enqueue(imageRecord(sequence = 2, imageID = "hero", carriesPayload = false))
    val missedResult = native.pollFrameResult()
    val missed = missedResult.value.imageAttachments.single()
    assertFalse(renderer.render(missed))
    native.poller.reportMissingImagePayloads(renderer.drainMissingPayloadIds())
    assertEquals(SwiftTUIFramePollResult.None, native.poller.poll(handle = 17))

    // Another paint of the same payload-less record is suppressed while the
    // request remains outstanding, including when native has no newer frame.
    assertFalse(renderer.render(missed))
    native.poller.reportMissingImagePayloads(renderer.drainMissingPayloadIds())
    native.poller.poll(handle = 17)
    assertEquals(
      listOf("""{"scope":"images","ids":["hero"]}"""),
      native.resyncRequests
    )

    native.enqueue(
      imageRecord(
        sequence = 2,
        generation = 3,
        imageID = "hero",
        carriesPayload = true
      )
    )
    val repairedResult = native.pollFrameResult()
    assertEquals(missedResult.value.sequence, repairedResult.value.sequence)
    assertTrue(repairedResult.isImagePayloadRepair)
    assertTrue(repairedResult.shouldPublishOver(missedResult.value))
    val recovered = repairedResult.value.imageAttachments.single()
    assertTrue(renderer.render(recovered))
    assertEquals(0, renderer.recycledReturnCount)

    // An unrelated same-sequence duplicate retains the ordinary host-state
    // suppression; only the outstanding-ID repair signal bypasses it.
    native.enqueue(
      imageRecord(
        sequence = 2,
        generation = 4,
        imageID = "hero",
        carriesPayload = true
      )
    )
    val duplicate = native.pollFrameResult()
    assertFalse(duplicate.isImagePayloadRepair)
    assertFalse(duplicate.shouldPublishOver(repairedResult.value))
  }
}

@RunWith(Parameterized::class)
class SwiftTUIImageCachePressureRecoveryTest(
  private val cacheBudget: Int
) {
  @Test
  fun classBMeasuresBlankAndResyncCountsAcrossTwelveMiBGalleryCycle() {
    val native = ImageRecoveryNative()
    val renderer = ModelRenderer(cacheBudget)
    val imageIDs = (0 until 12).map { "gallery-$it" }
    var sequence = 0
    var generation = 0
    var presentedFrame: SwiftTUIFrame? = null

    // First delivery carries 12 one-MiB decoded images (> 8 MiB total).
    for (id in imageIDs) {
      sequence += 1
      generation += 1
      native.enqueue(imageRecord(sequence, generation, id, carriesPayload = true))
      val delivered = native.pollFrameResult()
      assertTrue(delivered.shouldPublishOver(presentedFrame))
      presentedFrame = delivered.value
      assertTrue(renderer.render(presentedFrame.imageAttachments.single()))
    }

    var blankWithoutRecovery = 0
    var blankAfterRecovery = 0
    for (id in imageIDs) {
      sequence += 1
      generation += 1
      native.enqueue(imageRecord(sequence, generation, id, carriesPayload = false))
      val payloadlessResult = native.pollFrameResult()
      assertTrue(payloadlessResult.shouldPublishOver(presentedFrame))
      presentedFrame = payloadlessResult.value
      val payloadless = presentedFrame.imageAttachments.single()
      if (!renderer.render(payloadless)) {
        blankWithoutRecovery += 1
        native.poller.reportMissingImagePayloads(renderer.drainMissingPayloadIds())
        native.poller.poll(handle = 17)

        generation += 1
        native.enqueue(imageRecord(sequence, generation, id, carriesPayload = true))
        val repaired = native.pollFrameResult()
        assertTrue(repaired.isImagePayloadRepair)
        assertTrue(repaired.shouldPublishOver(presentedFrame))
        presentedFrame = repaired.value
        if (!renderer.render(presentedFrame.imageAttachments.single())) {
          blankAfterRecovery += 1
        }
      }
    }

    assertEquals(12, imageIDs.size)
    assertTrue(imageIDs.size * IMAGE_MIB > 8 * IMAGE_MIB)
    assertEquals(12, blankWithoutRecovery)
    assertEquals(0, blankAfterRecovery)
    assertEquals(blankWithoutRecovery, native.resyncRequests.size)
    assertEquals(0, renderer.recycledReturnCount)
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "cacheBudget={0}")
    fun cacheBudgets(): List<Array<Int>> =
      listOf(
        arrayOf(4 * IMAGE_MIB),
        arrayOf(8 * IMAGE_MIB)
      )
  }
}
