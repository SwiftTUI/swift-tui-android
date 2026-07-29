package sh.swifttui.android.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class HostResize(
  val columns: Int,
  val rows: Int,
  val cellPixelWidth: Double,
  val cellPixelHeight: Double
)

class SwiftTUIHostState internal constructor(
  private val createHost: () -> Long,
  private val clipboard: SwiftTUIClipboard? = null
) {
  var frame by mutableStateOf<SwiftTUIFrame?>(null)
    private set

  var lastError by mutableStateOf<String?>(null)
    private set

  private var handle by mutableLongStateOf(0L)
  private var lastResize: HostResize? = null
  private val missingImagePayloadIds = linkedSetOf<String>()
  private val webSurfaceSession = SwiftTUIWebSurfaceSession()
  private val framePoller = SwiftTUIFramePoller(
    session = webSurfaceSession,
    copyLatestFrame = SwiftTUIJni::copyLatestFrame,
    requestResync = SwiftTUIJni::requestResync
  )

  fun start() {
    if (handle == 0L) {
      handle = createHost()
      if (handle == 0L) {
        lastError = "SwiftTUI host could not be created."
        return
      }
    }
    // Declare wire capabilities before the scene starts — the Swift host
    // rejects declarations once running, and an older host library without
    // the entry point ignores this (defaults = today's wire bytes). The
    // declaration selects the converged web-surface wire, so the decoder
    // session's delta baseline resets with the scene.
    missingImagePayloadIds.clear()
    framePoller.reset()
    val declaration = SwiftTUIWireCapabilities.declarationJson().encodeToByteArray()
    SwiftTUIJni.declareCapabilities(handle, declaration, declaration.size)
    SwiftTUIJni.start(handle)
  }

  fun stop() {
    val currentHandle = handle
    if (currentHandle != 0L) {
      SwiftTUIJni.stop(currentHandle)
    }
  }

  fun destroy() {
    val currentHandle = handle
    handle = 0L
    lastResize = null
    missingImagePayloadIds.clear()
    if (currentHandle != 0L) {
      SwiftTUIJni.destroy(currentHandle)
    }
  }

  fun resize(
    columns: Int,
    rows: Int,
    cellPixelWidth: Double,
    cellPixelHeight: Double
  ) {
    val currentHandle = handle
    if (currentHandle == 0L) {
      return
    }

    val resize = HostResize(
      columns = columns.coerceAtLeast(1),
      rows = rows.coerceAtLeast(1),
      cellPixelWidth = cellPixelWidth.coerceAtLeast(1.0),
      cellPixelHeight = cellPixelHeight.coerceAtLeast(1.0)
    )
    if (resize == lastResize) {
      return
    }

    lastResize = resize
    SwiftTUIJni.resize(
      currentHandle,
      resize.columns,
      resize.rows,
      resize.cellPixelWidth,
      resize.cellPixelHeight
    )
  }

  fun sendInput(bytes: ByteArray) {
    val currentHandle = handle
    if (currentHandle != 0L && bytes.isNotEmpty()) {
      SwiftTUIJni.sendInput(currentHandle, bytes, bytes.size)
    }
  }

  internal fun reportMissingImagePayload(id: String) {
    missingImagePayloadIds.add(id)
  }

  /** Reads the system clipboard and delivers it to the app as a bracketed paste. */
  fun paste() {
    val text = clipboard?.read() ?: return
    sendInput(SwiftTUIInput.bracketedPaste(text))
  }

  suspend fun pollFrames() {
    while (currentCoroutineContext().isActive) {
      tickOnce()
      pollFrameOnce()
      drainClipboardWrite()
      delay(33L)
    }
  }

  /**
   * Drives the Swift main-actor executor once per poll. The embedded SwiftTUI
   * run loop has no OS run loop on Android to resume its `@MainActor`
   * continuations, so without this pump autonomous `.task` loops and animation
   * stay frozen even though input-driven frames still render. Runs on the
   * Android main thread (the poll loop's dispatcher), matching the thread the
   * host was created/started on.
   */
  private fun tickOnce() {
    val currentHandle = handle
    if (currentHandle != 0L) {
      SwiftTUIJni.tick(currentHandle)
    }
  }

  /** Forwards any app-requested copy to the system clipboard, draining it once. */
  private fun drainClipboardWrite() {
    val currentHandle = handle
    val clipboard = clipboard ?: return
    if (currentHandle == 0L) {
      return
    }

    val needed = SwiftTUIJni.copyClipboardText(currentHandle, null, 0)
    if (needed <= 0) {
      return
    }

    val bytes = ByteArray(needed)
    val copied = SwiftTUIJni.copyClipboardText(currentHandle, bytes, bytes.size)
    if (copied in 1..bytes.size) {
      clipboard.write(bytes.decodeToString(0, copied))
    }
  }

  private fun pollFrameOnce() {
    val currentHandle = handle
    if (currentHandle == 0L) {
      return
    }

    // Rendering happens after frame delivery. Drain its payload-less misses on
    // every host tick so a request is delivered even when native has no newer
    // frame to return.
    if (missingImagePayloadIds.isNotEmpty()) {
      framePoller.reportMissingImagePayloads(missingImagePayloadIds)
      missingImagePayloadIds.clear()
    }

    when (val result = framePoller.poll(currentHandle)) {
      SwiftTUIFramePollResult.None -> Unit
      is SwiftTUIFramePollResult.Frame -> {
        if (result.shouldPublishOver(frame)) {
          frame = result.value
        }
        lastError = null
      }
      is SwiftTUIFramePollResult.Error -> lastError = result.message
    }
  }
}

@Composable
fun rememberSwiftTUIHostState(): SwiftTUIHostState {
  val context = LocalContext.current
  val state = remember {
    SwiftTUIHostState(
      createHost = { SwiftTUIJni.createHost() },
      clipboard = AndroidSystemClipboard(context)
    )
  }

  DisposableEffect(state) {
    state.start()
    onDispose {
      state.destroy()
    }
  }

  LaunchedEffect(state) {
    state.pollFrames()
  }

  return state
}
