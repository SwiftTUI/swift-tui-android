package sh.swifttui.android.host

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A `content://` document read through the access Android granted this app.
 *
 * Nothing here extends that access: no persistable permission is taken, because
 * the import copies the bytes while the picker's grant is live and the runtime
 * only ever sees the copy.
 */
internal class SwiftTUIContentDocument(
  private val resolver: ContentResolver,
  private val uri: Uri
) : SwiftTUIDocumentSource {
  override val identity: String = uri.toString()

  override fun describe(): SwiftTUIDocumentDescription {
    var displayName: String? = null
    var sizeBytes: Long? = null
    resolver.query(
      uri,
      arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
      null,
      null,
      null
    )?.use { cursor ->
      if (cursor.moveToFirst()) {
        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
          displayName = cursor.getString(nameColumn)
        }
        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
          sizeBytes = cursor.getLong(sizeColumn)
        }
      }
    }
    return SwiftTUIDocumentDescription(
      displayName = displayName,
      mimeType = resolver.getType(uri),
      sizeBytes = sizeBytes
    )
  }

  override fun open(): InputStream =
    resolver.openInputStream(uri) ?: throw IOException("the provider returned no stream for $uri")
}

internal object SwiftTUIAndroidMimeTypes : SwiftTUIMimeTypeResolver {
  override fun mimeTypeForExtension(extension: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

  override fun extensionForMimeType(mimeType: String): String? =
    MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
}

/** What a host state needs to import documents: its storage and a URI reader. */
internal class SwiftTUIHostDocuments(
  val store: SwiftTUIDocumentImportStore,
  /** `null` for a URI that is not a `content://` URI. */
  val source: (Uri) -> SwiftTUIDocumentSource?
) {
  companion object {
    const val IMPORT_DIRECTORY = "swifttui-imports"

    fun android(context: Context): SwiftTUIHostDocuments {
      val appContext = context.applicationContext
      return SwiftTUIHostDocuments(
        store = SwiftTUIDocumentImportStore(
          // Resolved on first use, off the main thread: `getCacheDir` can
          // create the directory.
          rootDirectory = { File(appContext.cacheDir, IMPORT_DIRECTORY) },
          mimeTypes = SwiftTUIAndroidMimeTypes
        ),
        source = { uri ->
          if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            SwiftTUIContentDocument(appContext.contentResolver, uri)
          } else {
            null
          }
        }
      )
    }
  }
}
