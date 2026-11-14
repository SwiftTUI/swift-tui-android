package sh.swifttui.android.host

import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTUILinksTest {
  // MARK: URI policy and dispatch

  @Test fun admittedDestinationReachesTheOpenerExactlyAsProduced() {
    val exact = listOf(
      "https://example.test/docs?q=1&lang=sv#frag",
      "HTTPS://Example.Test/Caf%C3%A9",
      "http://localhost:8080/a/b;c=d",
      "https://example.test/café/😀",
      "mailto:someone@example.test?subject=Hello%20there",
      "tel:+46-8-123-456"
    )
    for (uri in exact) {
      val opener = RecordingOpener()
      val outcomes = mutableListOf<SwiftTUILinkOutcome>()
      val outcome = SwiftTUILinkDispatcher(opener, onOutcome = outcomes::add).dispatch(uri)

      assertEquals(SwiftTUILinkOutcome.Opened(uri), outcome)
      assertEquals("one platform dispatch for $uri", listOf(uri), opener.opened)
      assertEquals("one outcome for $uri", listOf(outcome), outcomes)
    }
  }

  @Test fun defaultPolicyAllowsWebAndContactSchemesOnly() {
    assertEquals(setOf("http", "https", "mailto", "tel"), SwiftTUILinkPolicy().allowedSchemes)

    val refused = mapOf(
      "javascript:alert(1)" to "javascript",
      "file:///data/data/app/secret" to "file",
      "content://com.example.provider/item/1" to "content",
      "intent://scan/#Intent;scheme=zxing;end" to "intent",
      "data:text/html,<script>1</script>" to "data",
      "ftp://example.test/file" to "ftp",
      "market://details?id=com.example" to "market",
      "MyApp://settings" to "myapp"
    )
    for ((uri, scheme) in refused) {
      val opener = RecordingOpener()
      val outcome = SwiftTUILinkDispatcher(opener).dispatch(uri)

      assertEquals(SwiftTUILinkOutcome.UnsupportedScheme(uri, scheme), outcome)
      assertTrue("$uri must not reach the platform", opener.opened.isEmpty())
    }
  }

  @Test fun anAppCanAllowItsOwnSchemesWithoutLosingValidation() {
    val policy = SwiftTUILinkPolicy(SwiftTUILinkPolicy.DEFAULT_ALLOWED_SCHEMES + "MyApp")
    val opener = RecordingOpener()
    val dispatcher = SwiftTUILinkDispatcher(opener, policy)

    assertEquals(
      SwiftTUILinkOutcome.Opened("myapp://settings"),
      dispatcher.dispatch("myapp://settings")
    )
    assertEquals(SwiftTUILinkOutcome.Malformed("myapp:"), dispatcher.dispatch("myapp:"))
    assertEquals(
      SwiftTUILinkOutcome.UnsupportedScheme("other://settings", "other"),
      dispatcher.dispatch("other://settings")
    )
    assertEquals(listOf("myapp://settings"), opener.opened)
  }

  @Test fun malformedDestinationsAreNeverDispatched() {
    val tooLong = "https://example.test/" + "a".repeat(SwiftTUILinkPolicy.MAX_URI_BYTES)
    val malformed = listOf(
      "",
      " ",
      "example.test/no-scheme",
      "/relative/path",
      "://example.test",
      "1http://example.test",
      "ht tp://example.test",
      "https:",
      "https://",
      "https:///path-without-host",
      "https:example.test",
      "http://?query-without-host",
      " https://example.test",
      "https://example.test ",
      "https://example.test/a b",
      "https://example.test/\nheader: injected",
      "https://example.test/\u0000",
      "https://example.test/\u001B]8;;\u001B\\",
      "https://example.test/\u0085",
      "mailto:",
      tooLong
    )
    for (uri in malformed) {
      val opener = RecordingOpener()
      val outcome = SwiftTUILinkDispatcher(opener).dispatch(uri)

      assertEquals("'$uri'", SwiftTUILinkOutcome.Malformed(uri), outcome)
      assertTrue("'$uri' must not reach the platform", opener.opened.isEmpty())
    }
  }

  @Test fun destinationAtTheByteLimitIsAdmittedAndOneByteMoreIsNot() {
    val prefix = "https://example.test/"
    val atLimit = prefix + "a".repeat(SwiftTUILinkPolicy.MAX_URI_BYTES - prefix.length)
    // Two UTF-8 bytes in one UTF-16 unit: the limit counts bytes.
    val overByOneByte = atLimit.dropLast(1) + "é"

    assertNull(SwiftTUILinkPolicy().refusal(atLimit))
    assertEquals(
      SwiftTUILinkOutcome.Malformed(overByOneByte),
      SwiftTUILinkPolicy().refusal(overByOneByte)
    )
  }

  @Test fun missingPlatformHandlerIsReportedAndLaterLinksStillOpen() {
    val opener = RecordingOpener(handles = { it != "mailto:nobody@example.test" })
    val outcomes = mutableListOf<SwiftTUILinkOutcome>()
    val dispatcher = SwiftTUILinkDispatcher(opener, onOutcome = outcomes::add)

    dispatcher.dispatch("mailto:nobody@example.test")
    dispatcher.dispatch("https://example.test")

    assertEquals(
      listOf(
        SwiftTUILinkOutcome.NoHandler("mailto:nobody@example.test"),
        SwiftTUILinkOutcome.Opened("https://example.test")
      ),
      outcomes
    )
  }

  @Test fun openerFailureIsContainedAndLaterLinksStillOpen() {
    val failure = SecurityException("not allowed to start activity")
    var fail = true
    val opened = mutableListOf<String>()
    val dispatcher = SwiftTUILinkDispatcher(
      opener = { uri ->
        if (fail) throw failure
        opened.add(uri)
      }
    )

    val failed = dispatcher.dispatch("https://example.test/first")
    fail = false
    val recovered = dispatcher.dispatch("https://example.test/second")

    assertTrue(failed is SwiftTUILinkOutcome.Failed)
    assertSame(failure, (failed as SwiftTUILinkOutcome.Failed).cause)
    assertEquals("https://example.test/first", failed.uri)
    assertEquals(SwiftTUILinkOutcome.Opened("https://example.test/second"), recovered)
    assertEquals(listOf("https://example.test/second"), opened)
  }

  // MARK: Platform opener

  @Test fun uriHandlerOpenerMapsTheNoHandlerSignalAndPropagatesOtherFailures() {
    val seen = mutableListOf<String>()
    val handler = object : UriHandler {
      override fun openUri(uri: String) {
        seen.add(uri)
        when (uri) {
          "tel:0" -> throw IllegalArgumentException("Can't open $uri.")
          "tel:1" -> throw IllegalStateException("activity is finishing")
        }
      }
    }
    val dispatcher = SwiftTUILinkDispatcher(SwiftTUIUriHandlerLinkOpener(handler))

    assertEquals(
      SwiftTUILinkOutcome.Opened("https://example.test"),
      dispatcher.dispatch("https://example.test")
    )
    assertEquals(SwiftTUILinkOutcome.NoHandler("tel:0"), dispatcher.dispatch("tel:0"))
    assertTrue(dispatcher.dispatch("tel:1") is SwiftTUILinkOutcome.Failed)
    assertEquals(listOf("https://example.test", "tel:0", "tel:1"), seen)
  }

  // MARK: Routing — one dispatcher per activation

  @Test fun runtimeRouteDispatchesEachQueuedDestinationOnceInOrder() {
    val native = FakeLinkQueue(supported = true)
    val opener = RecordingOpener()
    val router = SwiftTUILinkRouter(SwiftTUILinkDispatcher(opener), native::copyOpenLink)

    router.attach(HANDLE)
    assertEquals(SwiftTUILinkRoute.Runtime, router.route)
    assertEquals("attaching registers the host with one size query", 1, native.sizeQueries)

    native.enqueue("https://example.test/a")
    native.enqueue("mailto:b@example.test")
    router.drain(HANDLE)
    router.drain(HANDLE)

    assertEquals(listOf("https://example.test/a", "mailto:b@example.test"), opener.opened)
    assertTrue(native.pending.isEmpty())
  }

  @Test fun runtimeRouteIgnoresTheTapSoATapCannotDispatchTwice() {
    val native = FakeLinkQueue(supported = true)
    val opener = RecordingOpener()
    val router = SwiftTUILinkRouter(SwiftTUILinkDispatcher(opener), native::copyOpenLink)
    router.attach(HANDLE)

    // The tap lands on a hyperlink cell and the runtime activates that link.
    router.tapped("https://example.test/link")
    native.enqueue("https://example.test/link")
    router.drain(HANDLE)

    assertEquals(listOf("https://example.test/link"), opener.opened)
  }

  @Test fun runtimeRouteOpensNothingForADisabledOrCustomHandledLink() {
    val native = FakeLinkQueue(supported = true)
    val opener = RecordingOpener()
    val router = SwiftTUILinkRouter(SwiftTUILinkDispatcher(opener), native::copyOpenLink)
    router.attach(HANDLE)

    // A disabled link, or one with a custom open-link action, still paints a
    // hyperlink cell. The runtime queues nothing for it, so nothing opens.
    router.tapped("https://example.test/disabled")
    router.drain(HANDLE)

    assertTrue(opener.opened.isEmpty())
  }

  @Test fun runtimeRouteReportsPolicyRefusalsWithoutBlockingTheQueue() {
    val native = FakeLinkQueue(supported = true)
    val opener = RecordingOpener()
    val outcomes = mutableListOf<SwiftTUILinkOutcome>()
    val router = SwiftTUILinkRouter(
      SwiftTUILinkDispatcher(opener, onOutcome = outcomes::add),
      native::copyOpenLink
    )
    router.attach(HANDLE)

    native.enqueue("javascript:alert(1)")
    native.enqueueBytes(byteArrayOf('h'.code.toByte(), 0xFF.toByte()))
    native.enqueue("https://example.test/after")
    router.drain(HANDLE)

    assertEquals(
      listOf(
        SwiftTUILinkOutcome.UnsupportedScheme("javascript:alert(1)", "javascript"),
        SwiftTUILinkOutcome.Malformed(""),
        SwiftTUILinkOutcome.Opened("https://example.test/after")
      ),
      outcomes
    )
    assertEquals(listOf("https://example.test/after"), opener.opened)
  }

  @Test fun runtimeRouteStopsAtAnImplausibleSizeInsteadOfAllocatingIt() {
    val native = FakeLinkQueue(supported = true)
    val opener = RecordingOpener()
    val router = SwiftTUILinkRouter(SwiftTUILinkDispatcher(opener), native::copyOpenLink)
    router.attach(HANDLE)

    native.reportedSize = HostContract.RECORD_BYTES + 1
    router.drain(HANDLE)

    assertTrue(opener.opened.isEmpty())
    assertEquals("no copy is attempted", 0, native.copies)
  }

  @Test fun legacyRouteOpensTheTappedHyperlinkAndNeverPollsTheLibrary() {
    val native = FakeLinkQueue(supported = false)
    val opener = RecordingOpener()
    val router = SwiftTUILinkRouter(SwiftTUILinkDispatcher(opener), native::copyOpenLink)

    router.attach(HANDLE)
    assertEquals(SwiftTUILinkRoute.LegacyTap, router.route)

    router.tapped("https://example.test/legacy")
    router.tapped("javascript:alert(1)")
    router.drain(HANDLE)

    assertEquals(listOf("https://example.test/legacy"), opener.opened)
    assertEquals("only the attach probe reaches the library", 1, native.sizeQueries)
  }

  @Test fun routeFollowsTheLibraryOnEachAttach() {
    val native = FakeLinkQueue(supported = true)
    val router = SwiftTUILinkRouter(SwiftTUILinkDispatcher(RecordingOpener()), native::copyOpenLink)

    assertEquals("no library is assumed to have the operation", SwiftTUILinkRoute.LegacyTap, router.route)
    router.attach(HANDLE)
    assertEquals(SwiftTUILinkRoute.Runtime, router.route)
    native.supported = false
    router.attach(HANDLE)
    assertEquals(SwiftTUILinkRoute.LegacyTap, router.route)
  }

  // MARK: Legacy tap rule — drag versus tap, and frame changes mid-gesture

  @Test fun onlyAnUnmovedGestureOnAnUnchangedLinkIsATap() {
    val link = "https://example.test"

    assertEquals(link, SwiftTUILinkTap.target(pressed = link, moved = false, released = link))
    assertNull("a drag never opens", SwiftTUILinkTap.target(link, moved = true, released = link))
    assertNull("pressed on plain text", SwiftTUILinkTap.target(null, moved = false, released = link))
    assertNull("link gone at release", SwiftTUILinkTap.target(link, moved = false, released = null))
    assertNull(
      "another link under the finger at release",
      SwiftTUILinkTap.target(link, moved = false, released = "https://other.example.test")
    )
    assertNull(SwiftTUILinkTap.target("", moved = false, released = ""))
    assertNull(SwiftTUILinkTap.target(" ", moved = false, released = " "))
  }

  @Test fun tapHitTestFollowsTheFrameShowingAtTheRelease() {
    val session = SwiftTUIWebSurfaceSession()
    // Row 1: "ab" links to A across columns 1-2; row 2: a wide glyph linking to B.
    val pressedFrame = requireNotNull(
      session.decode(
        linkRecord(
          sequence = 1,
          links = """[[0,[[0,2,0]]],[1,[[0,2,1]]]]""",
          targets = """["https://a.example.test","https://b.example.test"]"""
        )
      )
    )
    val pressedA = pressedFrame.cellAt(2, 1)?.hyperlink
    val pressedB = pressedFrame.cellAt(2, 2)?.hyperlink
    assertEquals("https://a.example.test", pressedA)
    assertEquals("the wide glyph's covered column resolves to its link", "https://b.example.test", pressedB)
    assertNull(pressedFrame.cellAt(3, 1)?.hyperlink)

    // A repaint that keeps the links (a pressed highlight, say) still opens.
    val repainted = requireNotNull(
      session.decode(
        linkRecord(
          sequence = 2,
          links = """[[0,[[0,2,0]]],[1,[[0,2,1]]]]""",
          targets = """["https://a.example.test","https://b.example.test"]"""
        )
      )
    )
    assertEquals(
      pressedA,
      SwiftTUILinkTap.target(pressedA, moved = false, released = repainted.cellAt(2, 1)?.hyperlink)
    )

    // The content scrolled under the finger: row 1 now links to B, row 2 to nothing.
    val scrolled = requireNotNull(
      session.decode(
        linkRecord(
          sequence = 3,
          links = """[[0,[[0,2,0]]]]""",
          targets = """["https://b.example.test"]"""
        )
      )
    )
    assertEquals("https://b.example.test", scrolled.cellAt(2, 1)?.hyperlink)
    assertNull(scrolled.cellAt(2, 2)?.hyperlink)
    assertNull(
      "the pressed link is no longer under the finger",
      SwiftTUILinkTap.target(pressedA, moved = false, released = scrolled.cellAt(2, 1)?.hyperlink)
    )
    assertNull(
      SwiftTUILinkTap.target(pressedB, moved = false, released = scrolled.cellAt(2, 2)?.hyperlink)
    )
  }

  private fun linkRecord(sequence: Long, links: String, targets: String): String =
    SwiftTUIWebSurfaceSession.RECORD_PREFIX +
      """{"version":2,"sequence":$sequence,"width":4,"height":2,"styles":[null],""" +
      """"rows":[[[0,"a",1,0],[1,"b",1,0],[2,"c",1,0],[3," ",1,0]],""" +
      """[[0,"宽",2,0],[2,"e",1,0],[3," ",1,0]]],""" +
      """"links":$links,"linkTargets":$targets}""" + "\n"

  private class RecordingOpener(
    private val handles: (String) -> Boolean = { true }
  ) : SwiftTUILinkOpener {
    val opened = mutableListOf<String>()

    override fun open(uri: String): Boolean {
      if (!handles(uri)) {
        return false
      }
      opened.add(uri)
      return true
    }
  }

  /** The host library's side of the two-call open-link copy. */
  private class FakeLinkQueue(var supported: Boolean) {
    val pending = ArrayDeque<ByteArray>()
    var sizeQueries = 0
    var copies = 0
    var reportedSize: Int? = null

    fun enqueue(uri: String) = enqueueBytes(uri.encodeToByteArray())

    fun enqueueBytes(bytes: ByteArray) {
      pending.addLast(bytes)
    }

    fun copyOpenLink(handle: Long, buffer: ByteArray?, capacity: Int): Int {
      assertEquals(HANDLE, handle)
      if (!supported) {
        sizeQueries += 1
        return -1
      }
      val head = pending.firstOrNull()
      val needed = reportedSize ?: head?.size ?: 0
      if (buffer == null || capacity < needed || head == null) {
        sizeQueries += 1
        return needed
      }
      copies += 1
      head.copyInto(buffer)
      pending.removeFirst()
      return needed
    }
  }

  private companion object {
    const val HANDLE = 7L
  }
}
