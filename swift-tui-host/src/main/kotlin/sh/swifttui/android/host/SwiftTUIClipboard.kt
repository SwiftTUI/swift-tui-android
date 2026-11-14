package sh.swifttui.android.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The system clipboard, behind a small interface so [SwiftTUIHostState] stays
 * testable without Android. `write` receives text the running app placed on the
 * clipboard (drained from the host across the ABI); `read` backs paste.
 */
interface SwiftTUIClipboard {
  /** The text a paste delivers, or `null` when the clipboard offers none. */
  fun read(): String?

  /** Replaces the clipboard with [text]. An empty [text] clears it. */
  fun write(text: String)
}

class AndroidSystemClipboard(
  context: Context
) : SwiftTUIClipboard {
  private val appContext = context.applicationContext
  private val manager =
    appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

  /**
   * Paste is text only: the text of the primary clip's first item, the item a
   * Compose text field pastes. An absent clip, an empty text, and a clip whose
   * first item carries no text (a URI or an Intent) all read as `null`, so the
   * paste sends nothing. The host never resolves a content URI or an Intent
   * into text: that would read another app's provider on the UI thread and
   * inject bytes the user did not copy as text.
   */
  override fun read(): String? {
    val clip = manager?.primaryClip ?: return null
    if (clip.itemCount == 0) {
      return null
    }
    return clip.getItemAt(0)?.text?.toString()?.takeIf { it.isNotEmpty() }
  }

  /**
   * An empty [text] is the app clearing the clipboard, so the primary clip is
   * removed rather than replaced by an empty text item.
   */
  override fun write(text: String) {
    if (text.isEmpty()) {
      manager?.clearPrimaryClip()
    } else {
      manager?.setPrimaryClip(ClipData.newPlainText("SwiftTUI", text))
    }
  }
}
