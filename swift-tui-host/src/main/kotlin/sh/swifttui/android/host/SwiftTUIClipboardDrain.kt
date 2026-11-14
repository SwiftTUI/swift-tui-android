package sh.swifttui.android.host

/**
 * Forwards the app's clipboard writes from the native host to the system
 * clipboard, exactly once per write.
 *
 * The host keeps only its latest undrained write. A host library with the
 * take operation (ABI 1 minor 1) reports "nothing pending" as -1, so a byte
 * count of 0 is an empty write, the app clearing the clipboard, and is
 * delivered like any other. An older library offers only the copy operation,
 * whose 0 means "nothing pending"; it never admits an empty write.
 *
 * The native calls are parameters so this runs as a plain JVM unit test.
 */
internal class SwiftTUIClipboardDrain(
  private val clipboard: SwiftTUIClipboard,
  private val takeClipboardText: (Long, ByteArray?, Int) -> Int,
  private val copyClipboardText: (Long, ByteArray?, Int) -> Int
) {
  // The library is loaded once per process, so one unsupported answer holds.
  private var takeUnsupported = false

  /**
   * Tells the host that this client delivers empty writes; the host refuses
   * them until its take operation is first called. Call before the scene
   * starts so a write made while it starts is already admitted. Nothing is
   * drained here.
   */
  fun declare(handle: Long) {
    if (handle != 0L && !takeUnsupported) {
      takeUnsupported = takeClipboardText(handle, null, 0) == TAKE_UNSUPPORTED
    }
  }

  /** Delivers the pending write, if any. A destroyed host (handle 0) has none. */
  fun drain(handle: Long) {
    if (handle == 0L) {
      return
    }
    if (!takeUnsupported) {
      val needed = takeClipboardText(handle, null, 0)
      if (needed != TAKE_UNSUPPORTED) {
        if (needed >= 0) {
          take(handle, needed)
        }
        return
      }
      takeUnsupported = true
    }
    copy(handle)
  }

  private fun take(handle: Long, needed: Int) {
    // The JNI bridge needs a real array to copy into, even for no bytes.
    val bytes = ByteArray(needed.coerceAtLeast(1))
    val copied = takeClipboardText(handle, bytes, bytes.size)
    // A larger result means a newer, longer write replaced the measured one
    // and nothing was drained; the next poll delivers it.
    if (copied in 0..bytes.size) {
      clipboard.write(bytes.decodeToString(0, copied))
    }
  }

  private fun copy(handle: Long) {
    val needed = copyClipboardText(handle, null, 0)
    if (needed <= 0) {
      return
    }

    val bytes = ByteArray(needed)
    val copied = copyClipboardText(handle, bytes, bytes.size)
    if (copied in 1..bytes.size) {
      clipboard.write(bytes.decodeToString(0, copied))
    }
  }

  internal companion object {
    /** The JNI bridge's answer when the host library has no take operation. */
    const val TAKE_UNSUPPORTED = -2
  }
}
