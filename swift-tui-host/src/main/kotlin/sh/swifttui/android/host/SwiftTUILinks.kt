package sh.swifttui.android.host

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.UriHandler

/**
 * What happened to one link activation. Every activation that reaches the host
 * ends in exactly one outcome, and only [Opened] started anything on the
 * device. [uri] is the destination exactly as the runtime produced it.
 */
sealed interface SwiftTUILinkOutcome {
  val uri: String

  /** The policy admitted the destination and the opener handed it to the platform. */
  data class Opened(override val uri: String) : SwiftTUILinkOutcome

  /** Not an absolute URI the policy can classify. Nothing was dispatched. */
  data class Malformed(override val uri: String) : SwiftTUILinkOutcome

  /** A well-formed URI whose [scheme] the policy does not allow. Nothing was dispatched. */
  data class UnsupportedScheme(
    override val uri: String,
    val scheme: String
  ) : SwiftTUILinkOutcome

  /** The policy admitted the destination, and nothing on the device handles it. */
  data class NoHandler(override val uri: String) : SwiftTUILinkOutcome

  /** The policy admitted the destination and the opener threw [cause]. */
  data class Failed(
    override val uri: String,
    val cause: Throwable
  ) : SwiftTUILinkOutcome
}

/**
 * Which destinations the host may hand to the platform.
 *
 * A destination is admitted when it is at most [MAX_URI_BYTES] of UTF-8, holds
 * no whitespace or control character, starts with an RFC 3986 scheme and a
 * colon, has something after the colon, and names a scheme in [allowedSchemes]
 * (compared without regard to case). An `http` or `https` destination must also
 * name a host. The host never trims, decodes or re-encodes a destination: it is
 * dispatched exactly as produced, or not at all.
 */
class SwiftTUILinkPolicy(
  allowedSchemes: Set<String> = DEFAULT_ALLOWED_SCHEMES
) {
  val allowedSchemes: Set<String> = allowedSchemes.mapTo(linkedSetOf()) { it.lowercase() }

  /** The outcome that refuses [uri], or `null` when the policy admits it. */
  fun refusal(uri: String): SwiftTUILinkOutcome? {
    if (uri.isEmpty() || uri.any { it.isWhitespace() || it.isISOControl() }) {
      return SwiftTUILinkOutcome.Malformed(uri)
    }
    if (uri.length > MAX_URI_BYTES || uri.encodeToByteArray().size > MAX_URI_BYTES) {
      return SwiftTUILinkOutcome.Malformed(uri)
    }
    val colon = uri.indexOf(':')
    val scheme = if (colon > 0) uri.substring(0, colon) else ""
    val wellFormedScheme = scheme.isNotEmpty() &&
      scheme.first().isAsciiLetter() &&
      scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
    val rest = uri.substring(colon + 1)
    if (!wellFormedScheme || rest.isEmpty()) {
      return SwiftTUILinkOutcome.Malformed(uri)
    }
    val normalizedScheme = scheme.lowercase()
    if (normalizedScheme == "http" || normalizedScheme == "https") {
      val host = rest.removePrefix("//").takeWhile { it != '/' && it != '?' && it != '#' }
      if (!rest.startsWith("//") || host.isEmpty()) {
        return SwiftTUILinkOutcome.Malformed(uri)
      }
    }
    if (normalizedScheme !in allowedSchemes) {
      return SwiftTUILinkOutcome.UnsupportedScheme(uri, normalizedScheme)
    }
    return null
  }

  companion object {
    /** Web pages and the two contact schemes. An app adds its own through the constructor. */
    val DEFAULT_ALLOWED_SCHEMES: Set<String> = setOf("http", "https", "mailto", "tel")

    /** Matches the bound the Swift host places on a queued destination. */
    const val MAX_URI_BYTES = 8192
  }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/**
 * The platform half of a link activation, behind an interface so the policy
 * and the routing are testable without Android and an app can open links its
 * own way (a custom tab, an in-app screen).
 */
fun interface SwiftTUILinkOpener {
  /**
   * Opens [uri], which the policy has admitted, and returns `false` when
   * nothing on the device handles it. Runs on the main thread. A thrown
   * exception is reported as [SwiftTUILinkOutcome.Failed] and never reaches the
   * host's input or frame loop.
   */
  fun open(uri: String): Boolean
}

/**
 * Opens links through a Compose [UriHandler]: on Android, one `ACTION_VIEW`
 * intent started from the hosting activity. `UriHandler.openUri` reports a
 * destination nothing handles as an [IllegalArgumentException].
 */
class SwiftTUIUriHandlerLinkOpener(
  private val handler: UriHandler
) : SwiftTUILinkOpener {
  override fun open(uri: String): Boolean =
    try {
      handler.openUri(uri)
      true
    } catch (_: IllegalArgumentException) {
      false
    } catch (_: ActivityNotFoundException) {
      false
    }
}

/**
 * The one place a link activation becomes a platform dispatch: applies
 * [policy], calls [opener] at most once, and reports the outcome to
 * [onOutcome].
 */
class SwiftTUILinkDispatcher(
  private val opener: SwiftTUILinkOpener,
  val policy: SwiftTUILinkPolicy = SwiftTUILinkPolicy(),
  private val onOutcome: (SwiftTUILinkOutcome) -> Unit = {}
) {
  fun dispatch(uri: String): SwiftTUILinkOutcome {
    val outcome = policy.refusal(uri) ?: try {
      if (opener.open(uri)) SwiftTUILinkOutcome.Opened(uri) else SwiftTUILinkOutcome.NoHandler(uri)
    } catch (error: Exception) {
      SwiftTUILinkOutcome.Failed(uri, error)
    }
    onOutcome(outcome)
    return outcome
  }
}

/** Who decides that a gesture activated a link. One route is active per host session. */
internal enum class SwiftTUILinkRoute {
  /**
   * The runtime decides and queues the destination for this host. It honors a
   * custom open-link action and a disabled link, and covers key and semantic
   * activation as well as taps.
   */
  Runtime,

  /**
   * The host library predates the open-link operation, so the host opens the
   * hyperlink under a tap itself, as it did before the operation existed.
   */
  LegacyTap
}

/**
 * Delivers each link activation to the dispatcher exactly once, through the
 * single route the loaded host library supports.
 *
 * [copyOpenLink] is the JNI two-call copy: `-1` when the library lacks the
 * operation, otherwise the byte count of the oldest queued destination (`0`
 * when none), removing it only on a successful copy.
 */
internal class SwiftTUILinkRouter(
  private val dispatcher: SwiftTUILinkDispatcher,
  private val copyOpenLink: (handle: Long, buffer: ByteArray?, capacity: Int) -> Int
) {
  var route = SwiftTUILinkRoute.LegacyTap
    private set

  /**
   * Selects the route for [handle] and, on the runtime route, registers this
   * host as the link dispatcher. Call before the scene starts, so no activation
   * can precede the registration.
   */
  fun attach(handle: Long) {
    route = if (copyOpenLink(handle, null, 0) >= 0) {
      SwiftTUILinkRoute.Runtime
    } else {
      SwiftTUILinkRoute.LegacyTap
    }
  }

  /** Dispatches the runtime's queued destinations in request order, each once. */
  fun drain(handle: Long) {
    if (route != SwiftTUILinkRoute.Runtime) {
      return
    }
    repeat(MAX_REQUESTS_PER_DRAIN) {
      val needed = copyOpenLink(handle, null, 0)
      if (needed <= 0 || needed > HostContract.RECORD_BYTES) {
        return
      }
      val bytes = ByteArray(needed)
      if (copyOpenLink(handle, bytes, bytes.size) != needed) {
        return
      }
      // Bytes that are not UTF-8 cannot be a URI. They are reported as a
      // malformed, empty destination instead of being repaired into one.
      val uri = try {
        bytes.decodeToString(throwOnInvalidSequence = true)
      } catch (_: CharacterCodingException) {
        ""
      }
      dispatcher.dispatch(uri)
    }
  }

  /**
   * Legacy route only: opens [uri], the hyperlink a tap landed on. On the
   * runtime route this does nothing, because the runtime has already decided
   * whether that tap activates a link and a second dispatch would duplicate it.
   */
  fun tapped(uri: String) {
    if (route == SwiftTUILinkRoute.LegacyTap) {
      dispatcher.dispatch(uri)
    }
  }

  private companion object {
    /** The Swift host queues at most this many destinations between polls. */
    const val MAX_REQUESTS_PER_DRAIN = 16
  }
}

/** The legacy route's tap rule, kept pure so it is testable without Compose. */
internal object SwiftTUILinkTap {
  /**
   * The hyperlink a completed gesture activates, or `null`. A gesture is a tap
   * only when the pointer never left the pressed cell ([moved] is false), and
   * it activates a link only when that cell carried the same hyperlink at the
   * press ([pressed]) and in the frame showing at the release ([released]): a
   * frame that moved or replaced the link mid-gesture opens nothing.
   */
  fun target(pressed: String?, moved: Boolean, released: String?): String? =
    if (!moved && !pressed.isNullOrBlank() && pressed == released) pressed else null
}
