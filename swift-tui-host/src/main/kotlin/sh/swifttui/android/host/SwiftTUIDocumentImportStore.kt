package sh.swifttui.android.host

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Host-owned temporary storage for imported documents.
 *
 * Layout: `<root>/<session>/<n>/<display name>`. A session is one native host,
 * from creation to destruction; `<n>` counts that session's imports, so two
 * documents with one display name never collide and the name survives as the
 * last path component. A document is streamed into `<session>/<n>.partial` and
 * moved into place with one atomic rename, so a path the runtime receives
 * always names a complete file and a failed import leaves nothing behind.
 *
 * Ownership and cleanup:
 * - The store owns every file under `<root>`. Nothing else may write there.
 * - A session's files live until [endSession] or [clear].
 * - [sweepStaleSessions] deletes the sessions a killed process left behind:
 *   every child of `<root>` that no live store in this process owns. An app
 *   that hosts SwiftTUI in several processes must give each its own root.
 *
 * [materialize] and [discard] block on the provider and on disk; call them off
 * the main thread. The other functions only flip in-memory state and hand disk
 * work to [cleanupExecutor].
 */
internal class SwiftTUIDocumentImportStore(
  rootDirectory: () -> File,
  private val mimeTypes: SwiftTUIMimeTypeResolver = SwiftTUIMimeTypeResolver.None,
  private val cleanupExecutor: Executor = BackgroundCleanup,
  private val newSessionName: () -> String = { UUID.randomUUID().toString() }
) {
  sealed interface Outcome {
    /** [createdFile] is false when an identical retained document was reused. */
    class Materialized internal constructor(
      val document: SwiftTUIImportedDocument,
      val createdFile: Boolean,
      internal val session: Session
    ) : Outcome

    data class Rejected(
      val reason: SwiftTUIDocumentImportRejection,
      val detail: String
    ) : Outcome
  }

  internal class Session(val name: String) {
    var closed = false
    var nextIndex = 1
    var retainedBytes = 0L
    val entries = LinkedHashMap<String, Entry>()
  }

  internal class Entry(val document: SwiftTUIImportedDocument, val digest: String, val index: Int)

  private class Rejection(
    val reason: SwiftTUIDocumentImportRejection,
    val detail: String
  ) : Exception(detail)

  private class Copied(val sizeBytes: Long, val digest: String)

  private val root: File by lazy(rootDirectory)
  private val lock = Any()
  private var session: Session? = null

  /** Bytes of imported documents the current session holds. */
  val retainedBytes: Long
    get() = synchronized(lock) { session?.retainedBytes ?: 0L }

  /** Starts a new session, ending the previous one and deleting its files. */
  fun beginSession() {
    endSession()
    val next = Session(newSessionName())
    registerLive(next.name)
    synchronized(lock) { session = next }
  }

  /**
   * Ends the current session: an import still in flight is refused at commit,
   * and the session's files are deleted.
   */
  fun endSession() {
    val ended = synchronized(lock) {
      val current = session ?: return
      current.closed = true
      current.entries.clear()
      current.retainedBytes = 0L
      session = null
      current
    }
    cleanupExecutor.execute {
      deleteTree(directory(ended))
      unregisterLive(ended.name)
    }
  }

  /**
   * Deletes every document the current session holds. Only for an app that
   * knows the running scene no longer reads the paths it was given.
   */
  fun clear() {
    val (current, indices) = synchronized(lock) {
      val current = session ?: return
      val indices = current.entries.values.map { it.index }
      current.entries.clear()
      current.retainedBytes = 0L
      current to indices
    }
    cleanupExecutor.execute {
      indices.forEach { deleteTree(File(directory(current), it.toString())) }
    }
  }

  /** Deletes sessions that no live store in this process owns. */
  fun sweepStaleSessions() {
    cleanupExecutor.execute {
      root.listFiles()?.forEach { child ->
        if (!isLive(child.name)) deleteTree(child)
      }
    }
  }

  /** Whether [outcome] belongs to the session that is still running. */
  fun isCurrent(outcome: Outcome.Materialized): Boolean =
    synchronized(lock) { outcome.session === session && !outcome.session.closed }

  /**
   * Copies [source] into the current session under [policy]. Returns the
   * committed document or the reason nothing was kept; in the second case, and
   * when [isCancelled] turns true (which throws [CancellationException]), no
   * file remains.
   */
  fun materialize(
    source: SwiftTUIDocumentSource,
    policy: SwiftTUIDocumentImportPolicy,
    isCancelled: () -> Boolean = { false }
  ): Outcome {
    val current = synchronized(lock) { session }
      ?: return Outcome.Rejected(
        SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
        "the host has no import session"
      )
    var partial: File? = null
    return try {
      val description = reading { source.describe() }
      val baseName = SwiftTUIDocumentNames.sanitized(description.displayName)
      val mimeType = effectiveMimeType(baseName, description.mimeType)
      if (policy.acceptedMimeTypes.none { SwiftTUIDocumentNames.mimeTypeMatches(it, mimeType) }) {
        throw Rejection(
          SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE,
          "type $mimeType is not in the allow-list ${policy.acceptedMimeTypes}"
        )
      }
      val name = nameWithExtension(baseName, mimeType)
      val declaredBytes = description.sizeBytes?.takeIf { it >= 0 }
      if (declaredBytes != null && declaredBytes > policy.maxDocumentBytes) {
        throw Rejection(
          SwiftTUIDocumentImportRejection.TOO_LARGE,
          "the document reports $declaredBytes bytes; the cap is ${policy.maxDocumentBytes}"
        )
      }
      val (index, file) = createPartial(current)
      partial = file
      val copied = copy(source, file, policy.maxDocumentBytes, isCancelled)
      if (declaredBytes != null && copied.sizeBytes < declaredBytes) {
        throw Rejection(
          SwiftTUIDocumentImportRejection.IO_FAILURE,
          "the stream ended after ${copied.sizeBytes} of $declaredBytes bytes"
        )
      }
      commit(current, index, file, name, mimeType, copied, policy)
    } catch (rejection: Rejection) {
      Outcome.Rejected(rejection.reason, rejection.detail)
    } catch (failure: IOException) {
      Outcome.Rejected(SwiftTUIDocumentImportRejection.IO_FAILURE, describe(failure))
    } finally {
      // After a successful commit the partial file has been renamed away.
      partial?.delete()
    }
  }

  /** Rolls back a materialization that was never delivered. */
  fun discard(outcome: Outcome.Materialized) {
    if (!outcome.createdFile) return
    val owner = outcome.session
    val entry = synchronized(lock) {
      val entry = owner.entries.remove(outcome.document.path) ?: return
      owner.retainedBytes -= entry.document.sizeBytes
      entry
    }
    deleteTree(File(directory(owner), entry.index.toString()))
  }

  private fun directory(session: Session): File = File(root, session.name)

  private fun effectiveMimeType(name: String, reported: String?): String {
    val provided = SwiftTUIDocumentNames.normalizedMimeType(reported)
    if (provided != null && provided != SwiftTUIDocumentNames.GENERIC_MIME_TYPE) return provided
    val inferred = SwiftTUIDocumentNames.extension(name)
      ?.let(mimeTypes::mimeTypeForExtension)
      ?.let(SwiftTUIDocumentNames::normalizedMimeType)
    return inferred ?: provided ?: SwiftTUIDocumentNames.GENERIC_MIME_TYPE
  }

  /** A path carries its type only in its extension, so supply a missing one. */
  private fun nameWithExtension(name: String, mimeType: String): String {
    if (SwiftTUIDocumentNames.extension(name) != null) return name
    val extension = mimeTypes.extensionForMimeType(mimeType)
      ?.takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }
      ?: return name
    return SwiftTUIDocumentNames.sanitized("$name.$extension")
  }

  private fun createPartial(session: Session): Pair<Int, File> = synchronized(lock) {
    if (session.closed) throw hostStopped()
    val index = session.nextIndex++
    storing {
      val directory = directory(session)
      if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
        throw IOException("cannot create $directory")
      }
      val partial = File(directory, "$index.partial")
      if (!partial.createNewFile()) throw IOException("$partial already exists")
      index to partial
    }
  }

  private fun copy(
    source: SwiftTUIDocumentSource,
    partial: File,
    maxBytes: Long,
    isCancelled: () -> Boolean
  ): Copied {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    var emptyReads = 0
    val input: InputStream = reading { source.open() }
    try {
      storing {
        FileOutputStream(partial).use { output ->
          while (true) {
            if (isCancelled()) throw CancellationException("the document import was cancelled")
            // Never ask for more than one byte past the cap: the read is bounded
            // even when the provider reports no size, or the wrong one.
            val remaining = maxBytes - total
            val wanted = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
            val count = reading { input.read(buffer, 0, wanted) }
            if (count < 0) break
            if (count == 0) {
              emptyReads += 1
              if (emptyReads > MAX_EMPTY_READS) {
                throw Rejection(
                  SwiftTUIDocumentImportRejection.IO_FAILURE,
                  "the provider stream stopped making progress"
                )
              }
              continue
            }
            emptyReads = 0
            total += count
            if (total > maxBytes) {
              throw Rejection(
                SwiftTUIDocumentImportRejection.TOO_LARGE,
                "the document is larger than the cap of $maxBytes bytes"
              )
            }
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
          }
        }
      }
    } finally {
      runCatching { input.close() }
    }
    return Copied(total, digest.digest().joinToString("") { "%02x".format(it) })
  }

  private fun commit(
    session: Session,
    index: Int,
    partial: File,
    name: String,
    mimeType: String,
    copied: Copied,
    policy: SwiftTUIDocumentImportPolicy
  ): Outcome = synchronized(lock) {
    if (session.closed) throw hostStopped()
    // A repeated delivery of unchanged content reuses the retained file rather
    // than keeping a second copy.
    val retained = session.entries.values.firstOrNull {
      it.digest == copied.digest && it.document.displayName == name
    }
    if (retained != null) {
      if (File(retained.document.path).isFile) {
        return@synchronized Outcome.Materialized(retained.document, createdFile = false, session)
      }
      session.entries.remove(retained.document.path)
      session.retainedBytes -= retained.document.sizeBytes
    }
    if (session.retainedBytes + copied.sizeBytes > policy.maxRetainedBytes) {
      throw Rejection(
        SwiftTUIDocumentImportRejection.STORAGE_BUDGET_EXCEEDED,
        "${session.retainedBytes} bytes are retained; adding ${copied.sizeBytes} would exceed " +
          "the budget of ${policy.maxRetainedBytes}"
      )
    }
    val directory = File(directory(session), index.toString())
    val target = File(directory, name)
    storing {
      if (!directory.mkdir()) throw IOException("cannot create $directory")
      try {
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (failure: IOException) {
        directory.delete()
        throw failure
      }
    }
    val document = SwiftTUIImportedDocument(
      path = target.absolutePath,
      displayName = name,
      mimeType = mimeType,
      sizeBytes = copied.sizeBytes
    )
    session.entries[document.path] = Entry(document, copied.digest, index)
    session.retainedBytes += copied.sizeBytes
    Outcome.Materialized(document, createdFile = true, session)
  }

  private fun hostStopped() = Rejection(
    SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
    "the host stopped before the document could be delivered"
  )

  /** Classifies a failure of the document source the way `ContentResolver` reports it. */
  private inline fun <T> reading(block: () -> T): T =
    try {
      block()
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: SecurityException) {
      throw Rejection(SwiftTUIDocumentImportRejection.PERMISSION_DENIED, describe(failure))
    } catch (failure: FileNotFoundException) {
      throw Rejection(SwiftTUIDocumentImportRejection.NOT_FOUND, describe(failure))
    } catch (failure: IOException) {
      throw Rejection(SwiftTUIDocumentImportRejection.IO_FAILURE, describe(failure))
    } catch (failure: RuntimeException) {
      // A provider's own failure crosses Binder as an unchecked exception.
      throw Rejection(SwiftTUIDocumentImportRejection.IO_FAILURE, describe(failure))
    }

  /** Classifies a failure of the host's own storage. */
  private inline fun <T> storing(block: () -> T): T =
    try {
      block()
    } catch (failure: IOException) {
      throw Rejection(
        SwiftTUIDocumentImportRejection.IO_FAILURE,
        "import storage failed: ${describe(failure)}"
      )
    } catch (failure: SecurityException) {
      throw Rejection(
        SwiftTUIDocumentImportRejection.IO_FAILURE,
        "import storage failed: ${describe(failure)}"
      )
    }

  private fun describe(failure: Throwable): String =
    failure.message?.let { "${failure.javaClass.simpleName}: $it" } ?: failure.javaClass.simpleName

  internal companion object {
    const val COPY_BUFFER_BYTES = 64 * 1024
    const val MAX_EMPTY_READS = 64

    private val liveSessionNames = HashSet<String>()

    private fun registerLive(name: String) {
      synchronized(liveSessionNames) { liveSessionNames.add(name) }
    }

    private fun unregisterLive(name: String) {
      synchronized(liveSessionNames) { liveSessionNames.remove(name) }
    }

    private fun isLive(name: String): Boolean =
      synchronized(liveSessionNames) { name in liveSessionNames }

    /** Deletes [file] and everything under it without following symbolic links. */
    fun deleteTree(file: File) {
      val path = file.toPath()
      if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
      runCatching {
        Files.walkFileTree(
          path,
          object : SimpleFileVisitor<Path>() {
            override fun visitFile(entry: Path, attributes: BasicFileAttributes): FileVisitResult {
              runCatching { Files.deleteIfExists(entry) }
              return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(entry: Path, failure: IOException): FileVisitResult =
              FileVisitResult.CONTINUE

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
              runCatching { Files.deleteIfExists(directory) }
              return FileVisitResult.CONTINUE
            }
          }
        )
      }
    }

    /**
     * One idle-expiring background thread shared by every store: cleanup is
     * serialized and never runs on the main thread.
     */
    val BackgroundCleanup: Executor = ThreadPoolExecutor(
      0,
      1,
      10L,
      TimeUnit.SECONDS,
      LinkedBlockingQueue()
    ) { runnable ->
      Thread(runnable, "SwiftTUIDocumentImports").apply { isDaemon = true }
    }
  }
}
