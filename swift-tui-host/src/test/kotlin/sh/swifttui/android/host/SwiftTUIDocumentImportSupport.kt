package sh.swifttui.android.host

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.Executor

/** A document source with scripted metadata, content and failures. */
internal class FakeDocumentSource(
  private val displayName: String? = "notes.txt",
  private val mimeType: String? = "text/plain",
  private val declaredBytes: Long? = null,
  private val content: ByteArray = ByteArray(0),
  private val describeFailure: Throwable? = null,
  private val openFailure: Throwable? = null,
  private val stream: (() -> InputStream)? = null
) : SwiftTUIDocumentSource {
  var describeCount = 0
    private set
  var openCount = 0
    private set
  var lastStream: InputStream? = null
    private set

  override val identity: String = "content://fake.documents/document/${displayName.orEmpty()}"

  override fun describe(): SwiftTUIDocumentDescription {
    describeCount += 1
    describeFailure?.let { throw it }
    return SwiftTUIDocumentDescription(displayName, mimeType, declaredBytes)
  }

  override fun open(): InputStream {
    openCount += 1
    openFailure?.let { throw it }
    return (stream?.invoke() ?: ForwardOnlyStream(content)).also { lastStream = it }
  }
}

/**
 * A forward-only stream, as a pipe-backed provider hands out: no mark, no
 * seek, short reads. Records how many bytes the reader asked for and took.
 */
internal open class ForwardOnlyStream(
  content: ByteArray,
  private val chunkBytes: Int = 4096
) : InputStream() {
  private val source = ByteArrayInputStream(content)
  var bytesRead = 0L
    private set
  var closed = false
    private set

  override fun markSupported(): Boolean = false

  override fun read(): Int {
    val value = source.read()
    if (value >= 0) bytesRead += 1
    return value
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    onRead(bytesRead)
    val count = source.read(buffer, offset, minOf(length, chunkBytes))
    if (count > 0) bytesRead += count
    return count
  }

  override fun close() {
    closed = true
  }

  /** Called before every read with the bytes handed out so far. */
  protected open fun onRead(delivered: Long) = Unit
}

/** Fails, or runs a side effect, once [afterBytes] have been handed out. */
internal class InterruptedStream(
  content: ByteArray,
  private val afterBytes: Long,
  private val interrupt: () -> Unit
) : ForwardOnlyStream(content, chunkBytes = 100) {
  private var fired = false

  override fun onRead(delivered: Long) {
    if (!fired && delivered >= afterBytes) {
      fired = true
      interrupt()
    }
  }
}

/** A provider stream that never ends and never makes progress. */
internal class StalledStream : InputStream() {
  override fun read(): Int = 0

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
}

internal object FakeMimeTypes : SwiftTUIMimeTypeResolver {
  private val byExtension = mapOf(
    "txt" to "text/plain",
    "csv" to "text/csv",
    "json" to "application/json",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "gif" to "image/gif",
    "pdf" to "application/pdf"
  )

  override fun mimeTypeForExtension(extension: String): String? = byExtension[extension]

  override fun extensionForMimeType(mimeType: String): String? =
    byExtension.entries.firstOrNull { it.value == mimeType }?.key
}

internal val DirectExecutor = Executor { it.run() }

/** A temporary import root plus a store whose cleanup runs inline. */
internal class ImportFixture(sessionNames: List<String> = emptyList()) : AutoCloseable {
  val root: File = Files.createTempDirectory("swifttui-imports-test").toFile()
  private val names = sessionNames.iterator()
  private var counter = 0

  val store = newStore()

  fun newStore(): SwiftTUIDocumentImportStore = SwiftTUIDocumentImportStore(
    rootDirectory = { root },
    mimeTypes = FakeMimeTypes,
    cleanupExecutor = DirectExecutor,
    newSessionName = { if (names.hasNext()) names.next() else "session-${counter++}-${System.nanoTime()}" }
  )

  /** Every regular file under the import root, as root-relative paths. */
  fun files(): List<String> =
    root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()

  /** Every directory under the import root, as root-relative paths. */
  fun directories(): List<String> =
    root.walkTopDown().filter { it.isDirectory && it != root }
      .map { it.relativeTo(root).path }.sorted().toList()

  override fun close() {
    store.endSession()
    root.deleteRecursively()
  }
}

internal fun bytes(count: Int, seed: Int = 7): ByteArray =
  ByteArray(count) { ((it * 31 + seed) and 0xFF).toByte() }
