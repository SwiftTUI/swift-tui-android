package sh.swifttui.android.host

import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The delivery contract: a granted document becomes exactly one file drop, on
 * the host's thread, or nothing at all.
 */
class SwiftTUIDocumentImporterTest {
  private val fixture = ImportFixture(sessionNames = listOf("session"))
  private val store = fixture.store.also { it.beginSession() }
  private val policy = SwiftTUIDocumentImportPolicy(maxDocumentBytes = 1000, maxRetainedBytes = 2500)
  private val hostThread = Thread.currentThread()
  private val drops = CopyOnWriteArrayList<ByteArray>()
  private val dropThreads = CopyOnWriteArrayList<Thread>()

  @Volatile
  private var hostRunning = true

  private val importer = SwiftTUIDocumentImporter(
    store = store,
    isHostRunning = { hostRunning },
    deliver = { bytes ->
      drops += bytes
      dropThreads += Thread.currentThread()
    },
    isHostThread = { Thread.currentThread() === hostThread }
  )

  @After
  fun tearDown() {
    fixture.close()
  }

  private fun assertNothingDeliveredOrStored() {
    assertEquals(0, drops.size)
    assertEquals(emptyList<String>(), fixture.files())
    assertEquals(0L, store.retainedBytes)
  }

  private fun rejection(result: SwiftTUIDocumentImportResult): SwiftTUIDocumentImportRejection {
    assertTrue("expected a rejection, got $result", result is SwiftTUIDocumentImportResult.Rejected)
    return (result as SwiftTUIDocumentImportResult.Rejected).reason
  }

  @Test
  fun aDismissedPickerIsCancelledAndTouchesNothing(): Unit = runBlocking {
    assertSame(SwiftTUIDocumentImportResult.Cancelled, importer.import(null, policy))
    assertNothingDeliveredOrStored()
    assertEquals(emptyList<String>(), fixture.directories())
  }

  @Test
  fun aDocumentIsDeliveredOnceAsAFileDropOfTheCompleteFile(): Unit = runBlocking {
    val content = bytes(900)
    val result = importer.import(
      FakeDocumentSource("it's 写真 100%.png", "image/png", 900, content),
      policy
    )

    val document = (result as SwiftTUIDocumentImportResult.Delivered).document
    assertEquals(File(fixture.root, "session/1/it's 写真 100%.png").absolutePath, document.path)
    assertEquals("it's 写真 100%.png", document.displayName)
    assertEquals("image/png", document.mimeType)
    assertEquals(900L, document.sizeBytes)
    // The file was complete before its path left the host.
    assertArrayEquals(content, File(document.path).readBytes())
    assertEquals(1, drops.size)
    assertArrayEquals(SwiftTUIInput.fileDrop(document.path), drops.single())
    assertTrue(drops.single().decodeToString().contains("it%27s%20%E5%86%99%E7%9C%9F%20100%25.png"))
  }

  @Test
  fun providerAndDiskAccessRunOffTheHostThreadAndDeliveryRunsOnIt(): Unit = runBlocking {
    val providerThreads = CopyOnWriteArrayList<Thread>()
    val source = object : SwiftTUIDocumentSource {
      override val identity = "content://fake.documents/document/threads"

      override fun describe(): SwiftTUIDocumentDescription {
        providerThreads += Thread.currentThread()
        return SwiftTUIDocumentDescription("threads.txt", "text/plain", null)
      }

      override fun open(): InputStream {
        providerThreads += Thread.currentThread()
        return object : ForwardOnlyStream(bytes(500), chunkBytes = 100) {
          override fun onRead(delivered: Long) {
            providerThreads += Thread.currentThread()
          }
        }
      }
    }

    val result = importer.import(source, policy)

    assertTrue(result is SwiftTUIDocumentImportResult.Delivered)
    assertTrue("the provider was consulted", providerThreads.size >= 3)
    assertTrue("no provider call ran on the host thread", providerThreads.none { it === hostThread })
    assertEquals(listOf(hostThread), dropThreads.toList())
  }

  @Test
  fun aRejectedDocumentDeliversNothing(): Unit = runBlocking {
    assertEquals(
      SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE,
      rejection(importer.import(FakeDocumentSource("a.pdf", "application/pdf", content = bytes(9)), policy))
    )
    assertEquals(
      SwiftTUIDocumentImportRejection.TOO_LARGE,
      rejection(importer.import(FakeDocumentSource("big.txt", content = bytes(1001)), policy))
    )
    assertEquals(
      SwiftTUIDocumentImportRejection.PERMISSION_DENIED,
      rejection(importer.import(FakeDocumentSource(describeFailure = SecurityException("denied")), policy))
    )
    assertNothingDeliveredOrStored()
  }

  @Test
  fun aHostThatIsNotRunningRefusesWithoutReadingTheDocument(): Unit = runBlocking {
    hostRunning = false
    val source = FakeDocumentSource(content = bytes(10))
    assertEquals(
      SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
      rejection(importer.import(source, policy))
    )
    assertEquals(0, source.describeCount)
    assertNothingDeliveredOrStored()
  }

  @Test
  fun aHostThatStopsDuringTheCopyGetsNothingAndTheCopyIsRolledBack(): Unit = runBlocking {
    val source = FakeDocumentSource("late.txt", stream = {
      InterruptedStream(bytes(900), afterBytes = 300) { hostRunning = false }
    })
    assertEquals(
      SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
      rejection(importer.import(source, policy))
    )
    assertNothingDeliveredOrStored()
  }

  @Test
  fun aHostReplacedAfterTheCopyGetsNoPathFromTheOldSession(): Unit = runBlocking {
    // destroy() then start() land between the commit and the delivery: the
    // host is running again, but it is not the host the copy was made for.
    var checks = 0
    val restartingImporter = SwiftTUIDocumentImporter(
      store = store,
      isHostRunning = {
        checks += 1
        if (checks == 2) store.beginSession()
        true
      },
      deliver = { drops += it },
      isHostThread = { Thread.currentThread() === hostThread }
    )
    assertEquals(
      SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
      rejection(restartingImporter.import(FakeDocumentSource(content = bytes(10)), policy))
    )
    assertNothingDeliveredOrStored()
  }

  @Test
  fun importsNeverOverlapAndTheFirstStillDeliversOnce(): Unit = runBlocking {
    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slow = FakeDocumentSource("slow.txt", stream = {
      InterruptedStream(bytes(900), afterBytes = 300) {
        reading.countDown()
        assertTrue(release.await(10, TimeUnit.SECONDS))
      }
    })
    val first = async(start = CoroutineStart.UNDISPATCHED) { importer.import(slow, policy) }
    assertTrue("the first import is copying", reading.await(10, TimeUnit.SECONDS))

    // The same result delivered again, or a second pick, while the first copies.
    val duplicate = FakeDocumentSource("slow.txt", content = bytes(900))
    assertEquals(
      SwiftTUIDocumentImportRejection.IMPORT_IN_PROGRESS,
      rejection(importer.import(duplicate, policy))
    )
    assertEquals(0, duplicate.describeCount)
    assertEquals(0, drops.size)

    release.countDown()
    assertTrue(first.await() is SwiftTUIDocumentImportResult.Delivered)
    assertEquals(1, drops.size)
    assertEquals(listOf("session/1/slow.txt"), fixture.files())

    // The guard clears once the first import has finished.
    assertTrue(
      importer.import(FakeDocumentSource("next.txt", content = bytes(5)), policy)
        is SwiftTUIDocumentImportResult.Delivered
    )
    assertEquals(2, drops.size)
  }

  @Test
  fun cancellingTheCallerMidCopyLeavesNoFileAndDeliversNothing(): Unit = runBlocking {
    val reading = CountDownLatch(1)
    val release = CountDownLatch(1)
    val slow = FakeDocumentSource("slow.txt", stream = {
      InterruptedStream(bytes(900), afterBytes = 300) {
        reading.countDown()
        assertTrue(release.await(10, TimeUnit.SECONDS))
      }
    })
    var finished = false
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      importer.import(slow, policy)
      finished = true
    }
    assertTrue(reading.await(10, TimeUnit.SECONDS))

    job.cancel()
    release.countDown()
    job.join()

    assertTrue(job.isCancelled)
    assertFalse(finished)
    assertNothingDeliveredOrStored()
    // The host can import again afterwards.
    assertTrue(
      importer.import(FakeDocumentSource("next.txt", content = bytes(5)), policy)
        is SwiftTUIDocumentImportResult.Delivered
    )
  }

  @Test
  fun aCopyThatCompletesAsTheCallerIsCancelledIsRolledBack(): Unit = runBlocking {
    // Cancellation arrives after the last byte was read: the copy commits, but
    // its result is discarded on the way back to the caller.
    val parent = Job()
    val source = FakeDocumentSource("raced.txt", stream = {
      InterruptedStream(bytes(300), afterBytes = 300) { parent.cancel() }
    })
    var finished = false
    val job = launch(parent, start = CoroutineStart.UNDISPATCHED) {
      importer.import(source, policy)
      finished = true
    }
    job.join()

    assertTrue(job.isCancelled)
    assertFalse("a cancelled import must not return", finished)
    assertEquals("the whole document had been read", 300L, (source.lastStream as ForwardOnlyStream).bytesRead)
    assertNothingDeliveredOrStored()
  }

  @Test
  fun callingFromAnotherThreadIsRefusedBeforeAnythingIsRead(): Unit = runBlocking {
    val offThread = SwiftTUIDocumentImporter(store, { true }, { drops += it }, { false })
    val source = FakeDocumentSource(content = bytes(10))
    try {
      offThread.import(source, policy)
      fail("an import from the wrong thread must throw")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("thread that started the host"))
    }
    assertEquals(0, source.describeCount)
    assertNothingDeliveredOrStored()

    // A dismissed picker and a host that never started are answered, not thrown:
    // neither needs the host thread.
    assertSame(SwiftTUIDocumentImportResult.Cancelled, offThread.import(null, policy))
    val notStarted = SwiftTUIDocumentImporter(store, { false }, { drops += it }, { false })
    assertEquals(
      SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
      rejection(notStarted.import(source, policy))
    )
  }

  @Test
  fun aDispatcherThatResumesOffTheHostThreadNeverReachesTheHost(): Unit = runBlocking {
    var calls = 0
    val wandering = SwiftTUIDocumentImporter(
      store = store,
      isHostRunning = { true },
      deliver = { drops += it },
      // On the host thread when called, somewhere else when the copy returns.
      isHostThread = { calls++ == 0 }
    )
    try {
      wandering.import(FakeDocumentSource(content = bytes(10)), policy)
      fail("delivery from the wrong thread must throw")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message!!.contains("thread that started the host"))
    }
    assertNothingDeliveredOrStored()
  }

  @Test
  fun repeatedDeliveryOfUnchangedContentKeepsOneFileAndDeliversItsPathAgain(): Unit = runBlocking {
    val content = bytes(400)
    val first = importer.import(FakeDocumentSource("report.txt", content = content), policy)
      as SwiftTUIDocumentImportResult.Delivered
    val second = importer.import(FakeDocumentSource("report.txt", content = content), policy)
      as SwiftTUIDocumentImportResult.Delivered

    assertEquals(first.document, second.document)
    assertEquals(listOf("session/1/report.txt"), fixture.files())
    assertEquals(400L, store.retainedBytes)
    assertEquals(2, drops.size)
    assertArrayEquals(drops[0], drops[1])

    // An edited document is a new file; the first stays valid.
    val edited = importer.import(FakeDocumentSource("report.txt", content = bytes(400, seed = 9)), policy)
      as SwiftTUIDocumentImportResult.Delivered
    assertNotEquals(first.document.path, edited.document.path)
    assertEquals(listOf("session/1/report.txt", "session/3/report.txt"), fixture.files())
    assertArrayEquals(content, File(first.document.path).readBytes())
  }
}
