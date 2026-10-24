package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import org.json.JSONArray
import org.json.JSONObject

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1PollerTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (96..100).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    val native = A1Native(growFirstCopy = scenario == 98)
    val poller = native.makePoller()
    when (scenario) {
      96 -> {
        poller.reportMissingImagePayloads(listOf("b", "a", "b"))
        assertSame(SwiftTUIFramePollResult.None, poller.poll(1))
        assertEquals(listOf("a", "b"), (0..1).map { native.requests.single().getJSONArray("ids").getString(it) })
        assertSame(SwiftTUIFramePollResult.None, poller.poll(1))
        assertEquals(1, native.requests.size)
      }
      97 -> {
        poller.reportMissingImagePayloads(listOf("a")); poller.poll(1)
        poller.reset()
        poller.reportMissingImagePayloads(listOf("a")); poller.poll(1)
        assertEquals(2, native.requests.size)
        assertTrue(native.requests.all { it.getJSONArray("ids").getString(0) == "a" })
      }
      98 -> {
        native.enqueue(a1Full())
        val result = poller.poll(1) as SwiftTUIFramePollResult.Frame
        assertEquals(listOf("A", "B"), result.value.cells.map { it.character })
        assertEquals(2, native.copyCalls)
        assertTrue(native.requests.isEmpty())
      }
      99 -> {
        repeat(2) {
          native.enqueueRaw("not-a-surface-record")
          assertTrue(poller.poll(1) is SwiftTUIFramePollResult.Error)
        }
        assertEquals(1, native.requests.size)
        assertEquals("keyframe", native.requests.single().getString("scope"))
      }
      100 -> {
        val image = JSONObject("""{"id":"image","bounds":[0,0,1,1],"visibleBounds":[0,0,1,1]}""")
        native.enqueue(a1Full().put("images", JSONArray().put(image)))
        val first = poller.poll(1) as SwiftTUIFramePollResult.Frame
        assertFalse(first.isImagePayloadRepair)
        poller.reportMissingImagePayloads(listOf("image")); poller.poll(1)
        native.enqueue(a1Full().put("images", JSONArray().put(image.put("dataBase64", "eA=="))))
        val second = poller.poll(1) as SwiftTUIFramePollResult.Frame
        assertEquals(first.value.sequence, second.value.sequence)
        assertTrue(second.isImagePayloadRepair)
        assertTrue(second.shouldPublishOver(first.value))
        assertEquals(1, native.requests.size)
      }
      else -> error(id)
    }
  }
}
