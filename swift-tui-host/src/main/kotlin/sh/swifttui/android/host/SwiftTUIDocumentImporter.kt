package sh.swifttui.android.host

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Turns one granted document into one file drop.
 *
 * The document is copied off the calling thread, then delivered on it: the
 * path leaves this class only after the file is complete and committed, and
 * only while the host that will receive it is still the one that was running
 * when the copy began. Every other exit — rejection, cancellation, a host that
 * stopped mid-copy — rolls the copy back, so an import is delivered whole,
 * once, or not at all.
 *
 * Confined to the host's thread, like the host state that owns it.
 */
internal class SwiftTUIDocumentImporter(
  private val store: SwiftTUIDocumentImportStore,
  private val isHostRunning: () -> Boolean,
  private val deliver: (ByteArray) -> Unit,
  private val isHostThread: () -> Boolean = { true },
  private val ioContext: CoroutineContext = Dispatchers.IO
) {
  private var importInFlight = false

  suspend fun import(
    source: SwiftTUIDocumentSource?,
    policy: SwiftTUIDocumentImportPolicy
  ): SwiftTUIDocumentImportResult {
    if (source == null) {
      return SwiftTUIDocumentImportResult.Cancelled
    }
    if (!isHostRunning()) {
      return rejected(SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING, "the host is not running")
    }
    // Checked before anything is read: delivery will need this thread again.
    check(isHostThread()) { HOST_THREAD_MESSAGE }
    if (importInFlight) {
      return rejected(
        SwiftTUIDocumentImportRejection.IMPORT_IN_PROGRESS,
        "another document import has not finished"
      )
    }

    importInFlight = true
    // Holds a committed file until it is delivered, so every other way out of
    // this function (including cancellation that discards the copy's result)
    // can roll it back.
    val undelivered = AtomicReference<SwiftTUIDocumentImportStore.Outcome.Materialized?>(null)
    try {
      val outcome = withContext(ioContext) {
        val copyContext = coroutineContext
        store.materialize(source, policy) { !copyContext.isActive }.also { outcome ->
          if (outcome is SwiftTUIDocumentImportStore.Outcome.Materialized) undelivered.set(outcome)
        }
      }
      return when (outcome) {
        is SwiftTUIDocumentImportStore.Outcome.Rejected -> rejected(outcome.reason, outcome.detail)
        is SwiftTUIDocumentImportStore.Outcome.Materialized -> {
          // A dispatcher that resumed somewhere else must not reach the native host.
          check(isHostThread()) { HOST_THREAD_MESSAGE }
          val drop = SwiftTUIInput.fileDrop(outcome.document.path)
          when {
            !isHostRunning() || !store.isCurrent(outcome) -> rejected(
              SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
              "the host stopped before the document could be delivered"
            )
            drop.isEmpty() -> rejected(
              SwiftTUIDocumentImportRejection.IO_FAILURE,
              "the import path cannot be encoded as a file drop"
            )
            else -> {
              undelivered.set(null)
              deliver(drop)
              SwiftTUIDocumentImportResult.Delivered(outcome.document)
            }
          }
        }
      }
    } finally {
      undelivered.getAndSet(null)?.let { abandoned ->
        withContext(NonCancellable + ioContext) { store.discard(abandoned) }
      }
      importInFlight = false
    }
  }

  private fun rejected(
    reason: SwiftTUIDocumentImportRejection,
    detail: String
  ) = SwiftTUIDocumentImportResult.Rejected(reason, detail)

  private companion object {
    const val HOST_THREAD_MESSAGE =
      "importDocument must run on the thread that started the host (the Android main thread)"
  }
}
