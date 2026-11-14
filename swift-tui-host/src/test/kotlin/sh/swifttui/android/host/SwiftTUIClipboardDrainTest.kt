package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTUIClipboardDrainTest {
  @Test fun nonEmptyWriteReachesTheClipboardOnceAcrossPolls() {
    val host = FakeClipboardHost()
    val clipboard = RecordingClipboard()
    val drain = host.makeDrain(clipboard)

    drain.drain(HANDLE)
    assertTrue(clipboard.writes.isEmpty())

    assertTrue(host.write(HANDLE, "copied"))
    repeat(5) { drain.drain(HANDLE) }
    assertEquals(listOf("copied"), clipboard.writes)
    assertEquals("copied", clipboard.content)
  }

  @Test fun emptyWriteAfterNonEmptyBeforeTheDrainDeliversTheEmptyValue() {
    val host = FakeClipboardHost()
    val clipboard = RecordingClipboard(content = "older clip")
    val drain = host.makeDrain(clipboard)
    drain.declare(HANDLE)

    assertTrue(host.write(HANDLE, "secret"))
    assertTrue(host.write(HANDLE, ""))
    repeat(3) { drain.drain(HANDLE) }

    // The replaced text never reaches the clipboard; the clear does, once.
    assertEquals(listOf(""), clipboard.writes)
    assertNull(clipboard.content)
  }

  @Test fun emptyWriteAloneClearsTheClipboard() {
    val host = FakeClipboardHost()
    val clipboard = RecordingClipboard(content = "older clip")
    val drain = host.makeDrain(clipboard)
    drain.declare(HANDLE)

    assertTrue(host.write(HANDLE, ""))
    repeat(3) { drain.drain(HANDLE) }

    assertEquals(listOf(""), clipboard.writes)
    assertNull(clipboard.content)
    assertNull(clipboard.read())
  }

  @Test fun declaringBeforeStartAdmitsAnEmptyWriteMadeBeforeTheFirstPoll() {
    val host = FakeClipboardHost()
    val clipboard = RecordingClipboard()
    val drain = host.makeDrain(clipboard)

    // The host refuses an empty write from a client it has not seen take.
    assertFalse(host.write(HANDLE, ""))
    drain.declare(HANDLE)
    assertTrue(clipboard.writes.isEmpty())
    assertTrue(host.write(HANDLE, ""))
    drain.drain(HANDLE)
    assertEquals(listOf(""), clipboard.writes)
  }

  @Test fun unicodeMultilineAndControlTextIsDeliveredByteExact() {
    for (text in listOf("héllo\nwörld\n", "漢字\t👩‍💻", "a\u0000b", "\u001B[31mred", " ")) {
      val host = FakeClipboardHost()
      val clipboard = RecordingClipboard()
      val drain = host.makeDrain(clipboard)
      assertTrue(host.write(HANDLE, text))
      drain.drain(HANDLE)
      drain.drain(HANDLE)
      assertEquals(listOf(text), clipboard.writes)
    }
  }

  @Test fun writeReplacedBetweenTheQueryAndTheCopyDeliversOnlyTheLatest() {
    // Longer: the copy is undersized, drains nothing, and the next poll delivers it.
    var host = FakeClipboardHost()
    var clipboard = RecordingClipboard()
    var drain = host.makeDrain(clipboard)
    assertTrue(host.write(HANDLE, "old"))
    host.beforeNextTransfer = { assertTrue(host.write(HANDLE, "a much newer payload")) }
    drain.drain(HANDLE)
    assertTrue(clipboard.writes.isEmpty())
    drain.drain(HANDLE)
    assertEquals(listOf("a much newer payload"), clipboard.writes)

    // Shorter, or empty: the measured buffer already fits it.
    for (replacement in listOf("new", "")) {
      host = FakeClipboardHost()
      clipboard = RecordingClipboard(content = "older clip")
      drain = host.makeDrain(clipboard)
      assertTrue(host.write(HANDLE, "old payload"))
      host.beforeNextTransfer = { assertTrue(host.write(HANDLE, replacement)) }
      drain.drain(HANDLE)
      drain.drain(HANDLE)
      assertEquals(listOf(replacement), clipboard.writes)
    }

    // An empty write replaced by text before its copy.
    host = FakeClipboardHost()
    clipboard = RecordingClipboard()
    drain = host.makeDrain(clipboard)
    drain.declare(HANDLE)
    assertTrue(host.write(HANDLE, ""))
    host.beforeNextTransfer = { assertTrue(host.write(HANDLE, "xy")) }
    drain.drain(HANDLE)
    drain.drain(HANDLE)
    assertEquals(listOf("xy"), clipboard.writes)
  }

  @Test fun hostLibraryWithoutTakeKeepsTheCopyConventionAndIsAskedOnce() {
    val host = FakeClipboardHost(hasTake = false)
    val clipboard = RecordingClipboard(content = "older clip")
    val drain = host.makeDrain(clipboard)
    drain.declare(HANDLE)

    // An ABI 1.0 host cannot report an empty write, so it refuses one.
    assertFalse(host.write(HANDLE, ""))
    drain.drain(HANDLE)
    assertTrue(clipboard.writes.isEmpty())
    assertEquals("older clip", clipboard.content)

    assertTrue(host.write(HANDLE, "via copy"))
    repeat(3) { drain.drain(HANDLE) }
    assertEquals(listOf("via copy"), clipboard.writes)
    assertEquals(1, host.takeCalls)
  }

  @Test fun undeclaredDrainAgainstAHostWithoutTakeFallsBackOnTheSamePoll() {
    val host = FakeClipboardHost(hasTake = false)
    val clipboard = RecordingClipboard()
    val drain = host.makeDrain(clipboard)
    assertTrue(host.write(HANDLE, "first poll"))
    drain.drain(HANDLE)
    assertEquals(listOf("first poll"), clipboard.writes)
    assertEquals(1, host.takeCalls)
  }

  @Test fun destroyedHostNeverDeliversAWriteItStillHeld() {
    val host = FakeClipboardHost()
    val clipboard = RecordingClipboard(content = "user clip")
    val drain = host.makeDrain(clipboard)
    assertTrue(host.write(HANDLE, "pending at destroy"))

    // SwiftTUIHostState.destroy() zeroes its handle before the native destroy;
    // the poll loop then drains handle 0, and a recreated host has a new handle.
    host.destroy(HANDLE)
    drain.drain(0L)
    assertEquals(0, host.takeCalls + host.copyCalls)
    drain.declare(HANDLE + 1)
    repeat(3) { drain.drain(HANDLE + 1) }
    drain.drain(HANDLE)

    assertTrue(clipboard.writes.isEmpty())
    assertEquals("user clip", clipboard.content)
  }

  private companion object {
    const val HANDLE = 7L
  }
}

private class RecordingClipboard(var content: String? = null) : SwiftTUIClipboard {
  val writes = mutableListOf<String>()

  override fun read(): String? = content?.takeIf { it.isNotEmpty() }

  override fun write(text: String) {
    writes += text
    content = text.ifEmpty { null }
  }
}

/**
 * The clipboard half of the Swift `AndroidHostSceneHost`: one latest-write slot
 * per handle, the copy operation's "0 is nothing" convention, the take
 * operation's -1, and admission of an empty write only for a client that takes.
 */
private class FakeClipboardHost(private val hasTake: Boolean = true) {
  private val pending = mutableMapOf<Long, ByteArray>()
  private val takers = mutableSetOf<Long>()
  var takeCalls = 0
  var copyCalls = 0
  /** Runs once, just before the next call that passes a buffer. */
  var beforeNextTransfer: (() -> Unit)? = null

  fun write(handle: Long, text: String): Boolean {
    if (text.isEmpty() && handle !in takers) return false
    pending[handle] = text.encodeToByteArray()
    return true
  }

  fun destroy(handle: Long) {
    pending.remove(handle)
    takers.remove(handle)
  }

  fun makeDrain(clipboard: SwiftTUIClipboard) = SwiftTUIClipboardDrain(
    clipboard = clipboard,
    takeClipboardText = { handle, buffer, capacity ->
      takeCalls++
      if (!hasTake) {
        SwiftTUIClipboardDrain.TAKE_UNSUPPORTED
      } else {
        takers += handle
        transfer(handle, buffer, capacity) ?: -1
      }
    },
    copyClipboardText = { handle, buffer, capacity ->
      copyCalls++
      // The copy operation neither reports nor drains an empty write, and the
      // JNI copy bridge treats a zero capacity as a size query.
      if (pending[handle]?.isEmpty() != false) {
        0
      } else {
        transfer(handle, buffer.takeIf { capacity > 0 }, capacity) ?: 0
      }
    }
  )

  private fun transfer(handle: Long, buffer: ByteArray?, capacity: Int): Int? {
    if (buffer != null) {
      beforeNextTransfer?.also { beforeNextTransfer = null }?.invoke()
    }
    val bytes = pending[handle] ?: return null
    if (buffer == null || capacity < bytes.size) return bytes.size
    bytes.copyInto(buffer)
    pending.remove(handle)
    return bytes.size
  }
}
