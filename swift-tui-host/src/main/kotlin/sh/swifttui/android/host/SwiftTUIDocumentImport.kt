package sh.swifttui.android.host

import java.io.InputStream

/**
 * The resource and content policy for one document import.
 *
 * The runtime's drop destinations receive a filesystem path, never a
 * `content://` URI, so an import copies the document into a host-owned file.
 * This policy bounds that copy.
 *
 * @property maxDocumentBytes the largest document accepted, in bytes. The cap
 *   is enforced on the bytes actually read, so a provider that reports no size
 *   or a wrong size cannot exceed it.
 * @property maxRetainedBytes the most bytes of imported documents one host
 *   keeps on disk at a time. An import that would exceed it is rejected; the
 *   documents already imported stay valid.
 * @property acceptedMimeTypes the MIME allow-list: exact types such as
 *   `image/png`, subtype wildcards such as `text/&#42;`, or `&#42;/&#42;` for
 *   any type. Pass the same list to the system picker so unsupported documents
 *   are not offered; the import checks it again because a picker filter is
 *   advisory.
 */
data class SwiftTUIDocumentImportPolicy(
  val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
  val maxRetainedBytes: Long = DEFAULT_MAX_RETAINED_BYTES,
  val acceptedMimeTypes: List<String> = DEFAULT_ACCEPTED_MIME_TYPES
) {
  init {
    require(maxDocumentBytes >= 0) { "maxDocumentBytes must not be negative" }
    require(maxRetainedBytes >= 0) { "maxRetainedBytes must not be negative" }
  }

  companion object {
    /** 16 MiB. */
    const val DEFAULT_MAX_DOCUMENT_BYTES: Long = 16L * 1024 * 1024

    /** 64 MiB. */
    const val DEFAULT_MAX_RETAINED_BYTES: Long = 64L * 1024 * 1024

    /**
     * Text, and the still and animated raster formats SwiftTUI decodes from a
     * path. An app whose drop destinations read other formats widens the list.
     */
    val DEFAULT_ACCEPTED_MIME_TYPES: List<String> = listOf(
      "text/*",
      "application/json",
      "image/png",
      "image/jpeg",
      "image/gif"
    )
  }
}

/**
 * A document the host copied into its own storage and delivered to the running
 * app.
 *
 * @property path the absolute path the app's drop destination received. The
 *   file stays readable there until the host is destroyed or the app calls
 *   [SwiftTUIHostState.clearImportedDocuments].
 * @property displayName the provider's display name, reduced to one safe path
 *   component. It is the last component of [path].
 * @property mimeType the provider's MIME type, or the type inferred from the
 *   display name when the provider reported none.
 * @property sizeBytes the number of bytes copied.
 */
data class SwiftTUIImportedDocument(
  val path: String,
  val displayName: String,
  val mimeType: String,
  val sizeBytes: Long
)

/**
 * Why an import delivered nothing. A later release may add reasons: keep an
 * `else` branch when switching on a value of this type.
 */
enum class SwiftTUIDocumentImportRejection {
  /** The URI is not a `content://` URI. Other schemes are never read. */
  UNSUPPORTED_SOURCE,

  /** The document's type is outside the policy's MIME allow-list. */
  UNSUPPORTED_TYPE,

  /** The document is larger than the policy's per-document cap. */
  TOO_LARGE,

  /** Keeping the document would exceed the policy's retained-bytes budget. */
  STORAGE_BUDGET_EXCEEDED,

  /** Read access was never granted, or the grant was revoked. */
  PERMISSION_DENIED,

  /** The document no longer exists or cannot be opened as a byte stream. */
  NOT_FOUND,

  /** The provider or local storage failed, or the stream ended early. */
  IO_FAILURE,

  /** Another import on this host has not finished. Imports never overlap. */
  IMPORT_IN_PROGRESS,

  /** The host is not running, or stopped before the document could be delivered. */
  HOST_NOT_RUNNING
}

/** The outcome of [SwiftTUIHostState.importDocument]. */
sealed interface SwiftTUIDocumentImportResult {
  /** The document was copied and its path was sent to the app as one file drop. */
  data class Delivered(val document: SwiftTUIImportedDocument) : SwiftTUIDocumentImportResult

  /** The picker returned no document. Nothing was read, written or delivered. */
  data object Cancelled : SwiftTUIDocumentImportResult

  /**
   * Nothing was delivered and no file was left behind. [detail] is a
   * diagnostic for logs, not user-facing copy.
   */
  data class Rejected(
    val reason: SwiftTUIDocumentImportRejection,
    val detail: String
  ) : SwiftTUIDocumentImportResult
}

/**
 * One document the app has been granted access to, behind an Android-free
 * interface so the import can be exercised without a `ContentResolver`.
 *
 * Both functions can block on the provider; the import calls them off the main
 * thread only. They report failure the way `ContentResolver` does:
 * `SecurityException` when access is not granted, `FileNotFoundException` when
 * the document is missing, any other `IOException` or `RuntimeException` when
 * the provider fails.
 */
internal interface SwiftTUIDocumentSource {
  /** The document's identity for diagnostics (its content URI). */
  val identity: String

  fun describe(): SwiftTUIDocumentDescription

  /** Opens a new sequential stream. Seeking is never required. */
  fun open(): InputStream
}

internal data class SwiftTUIDocumentDescription(
  val displayName: String? = null,
  val mimeType: String? = null,
  val sizeBytes: Long? = null
)

/** The platform's MIME registry, so type inference is testable without Android. */
internal interface SwiftTUIMimeTypeResolver {
  fun mimeTypeForExtension(extension: String): String?

  fun extensionForMimeType(mimeType: String): String?

  companion object {
    val None = object : SwiftTUIMimeTypeResolver {
      override fun mimeTypeForExtension(extension: String): String? = null

      override fun extensionForMimeType(mimeType: String): String? = null
    }
  }
}

internal object SwiftTUIDocumentNames {
  const val FALLBACK_NAME = "document"
  const val MAX_NAME_BYTES = 120
  const val MAX_EXTENSION_LENGTH = 16
  const val GENERIC_MIME_TYPE = "application/octet-stream"

  /**
   * Reduces a provider-supplied display name to one safe path component.
   *
   * A display name is untrusted input: only its last path component is kept,
   * control, line-separator and bidirectional-override characters and unpaired
   * surrogates are removed, leading dots are dropped (no hidden, `.` or `..`
   * names), and the result is capped at [MAX_NAME_BYTES] UTF-8 bytes with its
   * extension preserved. Spaces, quotes and non-ASCII letters are kept: the
   * drop encoding carries them unchanged.
   */
  fun sanitized(raw: String?): String {
    val lastComponent = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val cleaned = StringBuilder(lastComponent.length)
    var index = 0
    while (index < lastComponent.length) {
      val character = lastComponent[index]
      if (character.isHighSurrogate() && index + 1 < lastComponent.length &&
        lastComponent[index + 1].isLowSurrogate()
      ) {
        cleaned.append(character).append(lastComponent[index + 1])
        index += 2
        continue
      }
      index += 1
      if (character.isSurrogate() || character.isISOControl() || character.isUnsafeFormat()) {
        continue
      }
      cleaned.append(character)
    }
    val name = cleaned.toString().trim().trimStart('.').trim()
    return truncated(name.ifEmpty { FALLBACK_NAME })
  }

  /** The lowercase extension of [name], when it has a plausible one. */
  fun extension(name: String): String? {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return null
    val extension = name.substring(dot + 1)
    if (extension.length > MAX_EXTENSION_LENGTH || !extension.all { it.isLetterOrDigit() }) {
      return null
    }
    return extension.lowercase()
  }

  /** Lowercases [type] and removes parameters; `null` when nothing remains. */
  fun normalizedMimeType(type: String?): String? =
    type?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

  /** Whether the allow-list entry [pattern] admits the normalized [type]. */
  fun mimeTypeMatches(pattern: String, type: String): Boolean {
    val normalizedPattern = normalizedMimeType(pattern) ?: return false
    if (normalizedPattern == "*/*" || normalizedPattern == "*") return true
    if (normalizedPattern.endsWith("/*")) {
      return type.startsWith(normalizedPattern.dropLast(1)) &&
        type.length > normalizedPattern.length - 1
    }
    return normalizedPattern == type
  }

  private fun truncated(name: String): String {
    if (name.encodeToByteArray().size <= MAX_NAME_BYTES) return name
    val extension = extension(name)
    val suffix = if (extension == null) "" else name.substring(name.lastIndexOf('.'))
    val stem = name.substring(0, name.length - suffix.length)
    val budget = MAX_NAME_BYTES - suffix.encodeToByteArray().size
    val kept = StringBuilder()
    var used = 0
    var index = 0
    while (index < stem.length) {
      val end = if (stem[index].isHighSurrogate()) index + 2 else index + 1
      val bytes = stem.substring(index, end).encodeToByteArray().size
      if (used + bytes > budget) break
      kept.append(stem, index, end)
      used += bytes
      index = end
    }
    val shortened = kept.toString().trimEnd()
    return (shortened.ifEmpty { FALLBACK_NAME }) + suffix
  }

  private fun Char.isUnsafeFormat(): Boolean =
    this == ' ' || this == ' ' || this in '‪'..'‮' ||
      this in '⁦'..'⁩'
}
