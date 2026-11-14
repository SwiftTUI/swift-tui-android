package sh.swifttui.android.host

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
  private val clipboard: SwiftTUIClipboard? = null,
  links: SwiftTUILinkDispatcher? = null,
  private val documents: SwiftTUIHostDocuments? = null
) {
  var frame by mutableStateOf<SwiftTUIFrame?>(null)
    private set

  var lastError by mutableStateOf<String?>(null)
    private set

  private var handle by mutableLongStateOf(0L)
  // The thread the native host was created on. Every native call belongs to it.
  private var hostThread: Thread? = null
  private var sceneRunning = false
  private val documentImporter = documents?.let { documents ->
    SwiftTUIDocumentImporter(
      store = documents.store,
      isHostRunning = { handle != 0L && sceneRunning },
      deliver = ::sendInput,
      isHostThread = { Thread.currentThread() === hostThread }
    )
  }
  private val accessibilityRequests = SwiftTUIAccessibilityRequests()
  private var lastResize: HostResize? = null
  private val missingImagePayloadIds = linkedSetOf<String>()
  private val webSurfaceSession = SwiftTUIWebSurfaceSession()
  private val linkRouter = links?.let { SwiftTUILinkRouter(it, SwiftTUIJni::copyOpenLink) }
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
      hostThread = Thread.currentThread()
      // A new native host is a new import session: the documents of an earlier
      // host, and of an earlier process that died without cleaning up, go.
      documents?.store?.beginSession()
      documents?.store?.sweepStaleSessions()
    }
    // Declare wire capabilities before the scene starts — the Swift host
    // rejects declarations once running, and an older host library without
    // the entry point ignores this (defaults = today's wire bytes). The
    // declaration selects the converged web-surface wire, so the decoder
    // session's delta baseline resets with the scene.
    missingImagePayloadIds.clear()
    accessibilityRequests.reset()
    framePoller.reset()
    frame = null
    val declaration = SwiftTUIWireCapabilities.declarationJson().encodeToByteArray()
    SwiftTUIJni.declareCapabilities(handle, declaration, declaration.size)
    // Before the scene starts, so the runtime never sees a link activation
    // without its dispatcher registered.
    linkRouter?.attach(handle)
    SwiftTUIJni.start(handle)
    sceneRunning = true
  }

  fun stop() {
    accessibilityRequests.reset()
    sceneRunning = false
    val currentHandle = handle
    if (currentHandle != 0L) {
      SwiftTUIJni.stop(currentHandle)
    }
  }

  fun destroy() {
    accessibilityRequests.reset()
    sceneRunning = false
    // The runtime that could read the imported documents is going away.
    documents?.store?.endSession()
    val currentHandle = handle
    handle = 0L
    frame = null
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

  /** Returns queue acceptance; the frame response carries runtime acceptance. */
  fun sendAccessibilityAction(request: SwiftTUIAccessibilityActionRequest): Boolean {
    val currentHandle = handle
    val node = frame?.accessibilityNodes?.firstOrNull { it.actionTarget == request.target }
    if (currentHandle == 0L || node == null || node.hidden || !node.isEnabled ||
      request.action !in node.actions) return false
    val id = accessibilityRequests.issue(request.target) ?: return false
    val bytes = request.encode(id)
    val accepted = bytes != null && SwiftTUIJni.accessibilityAction(currentHandle, bytes, bytes.size) == 1
    if (!accepted) accessibilityRequests.cancel(id)
    return accepted
  }

  internal fun reportMissingImagePayload(id: String) {
    missingImagePayloadIds.add(id)
  }

  /**
   * Reports the hyperlink a tap landed on. Opens it only for a host library
   * without the open-link operation; otherwise the runtime owns that decision
   * and this call does nothing.
   */
  internal fun linkTapped(uri: String) {
    linkRouter?.tapped(uri)
  }

  /** Reads the system clipboard and delivers it to the app as a bracketed paste. */
  fun paste() {
    val text = clipboard?.read() ?: return
    sendInput(SwiftTUIInput.bracketedPaste(text))
  }

  /**
   * Imports the document a system picker returned and delivers it to the
   * running app as one file drop.
   *
   * The app decides when to show the picker, for example with
   * `ActivityResultContracts.OpenDocument` and the policy's
   * [SwiftTUIDocumentImportPolicy.acceptedMimeTypes]; this function is the
   * import step for the single `content://` URI that comes back. Pass `null`
   * for a dismissed picker.
   *
   * **Materialization.** A drop destination receives a filesystem path, and a
   * `content://` URI is not one. The document is therefore read through the
   * access Android granted the app and copied into a host-owned file under the
   * app's cache directory, bounded by [policy]. Only that file's path reaches
   * the runtime, as the same `file://` paste a terminal produces for a dropped
   * file. No persistable URI permission is taken.
   *
   * **Lifetime.** The file stays readable at its delivered path until this
   * host state is destroyed, or until [clearImportedDocuments]. The host
   * deletes it then, and deletes at the next host start whatever a killed
   * process left behind. The operating system may also evict cache files when
   * storage runs short.
   *
   * **Threading.** Call this on the main thread, as for every other function
   * of this class. Provider and disk access run on `Dispatchers.IO`; the
   * function suspends until the document is delivered or refused.
   *
   * **Outcomes.** Exactly one of:
   * - [SwiftTUIDocumentImportResult.Delivered]: the complete document was
   *   copied and its path was queued for the app once. Delivery does not report
   *   whether a drop destination took it; as on a terminal, a drop that no
   *   destination claims falls through to the runtime's text-paste handling.
   * - [SwiftTUIDocumentImportResult.Cancelled]: [uri] was `null`.
   * - [SwiftTUIDocumentImportResult.Rejected]: nothing was delivered and no
   *   file, partial or whole, was kept. See [SwiftTUIDocumentImportRejection].
   *
   * Cancelling the calling coroutine also leaves no file and delivers nothing.
   * Imports on one host never overlap: a call made while another is in flight
   * is rejected. Importing unchanged content under the same name again
   * delivers the already retained file rather than keeping a second copy.
   */
  suspend fun importDocument(
    uri: Uri?,
    policy: SwiftTUIDocumentImportPolicy = SwiftTUIDocumentImportPolicy()
  ): SwiftTUIDocumentImportResult {
    val documents = documents
    val importer = documentImporter
    if (documents == null || importer == null) {
      return SwiftTUIDocumentImportResult.Rejected(
        SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
        "this host state has no document storage"
      )
    }
    if (uri == null) {
      return importer.import(null, policy)
    }
    val source = documents.source(uri)
      ?: return SwiftTUIDocumentImportResult.Rejected(
        SwiftTUIDocumentImportRejection.UNSUPPORTED_SOURCE,
        "only content:// URIs are imported"
      )
    return importer.import(source, policy)
  }

  /**
   * Deletes every document this host has imported and frees its retained-bytes
   * budget. Call it only when the running app no longer reads the paths it was
   * given; a path delivered earlier stops resolving.
   */
  fun clearImportedDocuments() {
    documents?.store?.clear()
  }

  suspend fun pollFrames() {
    while (currentCoroutineContext().isActive) {
      tickOnce()
      pollFrameOnce()
      drainClipboardWrite()
      drainLinkRequests()
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

  /** Dispatches the links the runtime asked this host to open, each once. */
  private fun drainLinkRequests() {
    val currentHandle = handle
    if (currentHandle != 0L) {
      linkRouter?.drain(currentHandle)
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
          accessibilityRequests.observeEpoch(framePoller.epoch)
          accessibilityRequests.retainTargets(result.value.accessibilityNodes.mapNotNull { it.actionTarget }.toSet())
          frame = result.value.copy(accessibilityActionResponse = accessibilityRequests.accept(result.value.accessibilityActionResponse))
        }
        lastError = null
      }
      is SwiftTUIFramePollResult.Error -> lastError = result.message
    }
  }
}

/**
 * A host state that opens links under the default [SwiftTUILinkPolicy] through
 * the composition's [LocalUriHandler], logging every activation that opened
 * nothing.
 */
@Composable
fun rememberSwiftTUIHostState(): SwiftTUIHostState {
  val uriHandler = LocalUriHandler.current
  val links = remember(uriHandler) {
    SwiftTUILinkDispatcher(
      opener = SwiftTUIUriHandlerLinkOpener(uriHandler),
      onOutcome = ::logUnopenedLink
    )
  }
  return rememberSwiftTUIHostState(links)
}

/**
 * A host state whose link activations go through [links]: the app's own
 * policy, opener and outcome listener. The dispatcher in effect when the state
 * is first remembered is the one it keeps.
 */
@Composable
fun rememberSwiftTUIHostState(links: SwiftTUILinkDispatcher): SwiftTUIHostState {
  val context = LocalContext.current
  val state = remember {
    SwiftTUIHostState(
      createHost = { SwiftTUIJni.createHost() },
      clipboard = AndroidSystemClipboard(context),
      links = links,
      documents = SwiftTUIHostDocuments.android(context)
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

/**
 * The default record of a link that opened nothing. It names the outcome and
 * the scheme only: a destination can carry a token or an address that does not
 * belong in a device log.
 */
private fun logUnopenedLink(outcome: SwiftTUILinkOutcome) {
  val reason = when (outcome) {
    is SwiftTUILinkOutcome.Opened -> return
    is SwiftTUILinkOutcome.Malformed -> "malformed destination"
    is SwiftTUILinkOutcome.UnsupportedScheme -> "scheme '${outcome.scheme}' is not allowed"
    is SwiftTUILinkOutcome.NoHandler -> "no app handles '${outcome.uri.substringBefore(':')}:'"
    is SwiftTUILinkOutcome.Failed -> "opener failed with ${outcome.cause.javaClass.name}"
  }
  Log.w("SwiftTUILinks", "Link not opened: $reason")
}
