package sh.swifttui.android.host

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
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
 * Boundary tests for the bounded temporary materialization: what is accepted,
 * what is refused, and that a refusal never leaves a file behind.
 */
class SwiftTUIDocumentImportStoreTest {
  private val fixture = ImportFixture(sessionNames = listOf("session"))
  private val store = fixture.store.also { it.beginSession() }
  private val policy = SwiftTUIDocumentImportPolicy(maxDocumentBytes = 1000, maxRetainedBytes = 2500)

  @After
  fun tearDown() {
    fixture.close()
  }

  private fun materialized(
    source: SwiftTUIDocumentSource,
    policy: SwiftTUIDocumentImportPolicy = this.policy
  ): SwiftTUIDocumentImportStore.Outcome.Materialized {
    val outcome = store.materialize(source, policy)
    assertTrue("expected a materialized document, got $outcome",
      outcome is SwiftTUIDocumentImportStore.Outcome.Materialized)
    return outcome as SwiftTUIDocumentImportStore.Outcome.Materialized
  }

  private fun rejected(
    source: SwiftTUIDocumentSource,
    reason: SwiftTUIDocumentImportRejection,
    policy: SwiftTUIDocumentImportPolicy = this.policy
  ): SwiftTUIDocumentImportStore.Outcome.Rejected {
    val outcome = store.materialize(source, policy)
    assertTrue("expected $reason, got $outcome",
      outcome is SwiftTUIDocumentImportStore.Outcome.Rejected)
    outcome as SwiftTUIDocumentImportStore.Outcome.Rejected
    assertEquals(outcome.detail, reason, outcome.reason)
    return outcome
  }

  private fun assertNothingStored() {
    assertEquals(emptyList<String>(), fixture.files())
    assertEquals(0L, store.retainedBytes)
  }

  // Content, name and type

  @Test
  fun aDocumentIsCopiedWholeUnderItsDisplayName() {
    val content = bytes(700)
    val source = FakeDocumentSource("My Report (final).csv", "text/csv", 700, content)

    val document = materialized(source).document

    assertEquals(File(fixture.root, "session/1/My Report (final).csv").absolutePath, document.path)
    assertEquals("My Report (final).csv", document.displayName)
    assertEquals("text/csv", document.mimeType)
    assertEquals(700L, document.sizeBytes)
    assertArrayEquals(content, File(document.path).readBytes())
    assertEquals(listOf("session/1/My Report (final).csv"), fixture.files())
    assertEquals(700L, store.retainedBytes)
    assertTrue("the provider stream is closed", (source.lastStream as ForwardOnlyStream).closed)
  }

  @Test
  fun anEmptyDocumentIsAValidImport() {
    val document = materialized(FakeDocumentSource("empty.txt", "text/plain", 0, ByteArray(0))).document
    assertEquals(0L, document.sizeBytes)
    assertEquals(0L, File(document.path).length())
  }

  @Test
  fun aNonSeekableStreamOfUnknownSizeIsImported() {
    val content = bytes(999)
    // No declared size, no mark/seek, short reads: a pipe-backed provider.
    val source = FakeDocumentSource("stream.txt", "text/plain", null, stream = {
      ForwardOnlyStream(content, chunkBytes = 7)
    })
    val document = materialized(source).document
    assertArrayEquals(content, File(document.path).readBytes())
    assertEquals(999L, document.sizeBytes)
  }

  @Test
  fun aHostileDisplayNameCannotLeaveTheSession() {
    val document = materialized(
      FakeDocumentSource("../../../escaped.txt", "text/plain", content = bytes(3))
    ).document
    assertEquals(File(fixture.root, "session/1/escaped.txt").absolutePath, document.path)
    assertEquals(listOf("session/1/escaped.txt"), fixture.files())
    assertEquals(fixture.root.canonicalFile, File(document.path).canonicalFile.parentFile.parentFile.parentFile)
  }

  @Test
  fun aMissingDisplayNameFallsBackAndTakesItsExtensionFromTheType() {
    val unnamed = materialized(FakeDocumentSource(null, "image/png", content = bytes(4))).document
    assertEquals("document.png", unnamed.displayName)
    val bare = materialized(FakeDocumentSource("IMG_0042", "image/jpeg", content = bytes(5))).document
    assertEquals("IMG_0042.jpg", bare.displayName)
    // A name that already has an extension is left alone.
    val named = materialized(FakeDocumentSource("scan.txt", "image/png", content = bytes(6))).document
    assertEquals("scan.txt", named.displayName)
    assertEquals("image/png", named.mimeType)
  }

  @Test
  fun twoDocumentsWithOneNameGetSeparateDirectories() {
    val first = materialized(FakeDocumentSource("same.txt", content = bytes(10, seed = 1))).document
    val second = materialized(FakeDocumentSource("same.txt", content = bytes(10, seed = 2))).document
    assertNotEquals(first.path, second.path)
    assertEquals(listOf("session/1/same.txt", "session/2/same.txt"), fixture.files())
    assertArrayEquals(bytes(10, seed = 1), File(first.path).readBytes())
    assertArrayEquals(bytes(10, seed = 2), File(second.path).readBytes())
  }

  // MIME allow-list

  @Test
  fun aTypeOutsideTheAllowListIsRefusedBeforeTheContentIsOpened() {
    val source = FakeDocumentSource("setup.pdf", "application/pdf", content = bytes(10))
    rejected(source, SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE)
    assertEquals(0, source.openCount)
    assertNothingStored()
  }

  @Test
  fun aMissingOrGenericTypeIsInferredFromTheExtension() {
    val untyped = materialized(FakeDocumentSource("table.csv", null, content = bytes(3))).document
    assertEquals("text/csv", untyped.mimeType)
    val generic = materialized(
      FakeDocumentSource("photo.PNG", "application/octet-stream", content = bytes(4))
    ).document
    assertEquals("image/png", generic.mimeType)
    // The inferred type is still subject to the allow-list.
    rejected(
      FakeDocumentSource("manual.pdf", "application/octet-stream", content = bytes(5)),
      SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE
    )
  }

  @Test
  fun anUnknownTypeIsOpaqueBinaryAndRefusedByDefault() {
    rejected(
      FakeDocumentSource("blob", null, content = bytes(5)),
      SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE
    )
    rejected(
      FakeDocumentSource("blob.xyz", "application/octet-stream", content = bytes(5)),
      SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE
    )
    assertNothingStored()
    // An app that widens the allow-list takes it.
    val any = policy.copy(acceptedMimeTypes = listOf("*/*"))
    assertEquals("application/octet-stream",
      materialized(FakeDocumentSource("blob", null, content = bytes(5)), any).document.mimeType)
  }

  @Test
  fun typeParametersAndCaseDoNotDefeatTheAllowList() {
    val document = materialized(
      FakeDocumentSource("notes.txt", "Text/Plain; charset=UTF-8", content = bytes(3))
    ).document
    assertEquals("text/plain", document.mimeType)
  }

  @Test
  fun anEmptyAllowListAcceptsNothing() {
    rejected(
      FakeDocumentSource("notes.txt", "text/plain", content = bytes(3)),
      SwiftTUIDocumentImportRejection.UNSUPPORTED_TYPE,
      policy.copy(acceptedMimeTypes = emptyList())
    )
  }

  // Size cap

  @Test
  fun aDocumentExactlyAtTheCapIsAccepted() {
    val content = bytes(1000)
    val document = materialized(FakeDocumentSource("cap.txt", declaredBytes = 1000, content = content)).document
    assertArrayEquals(content, File(document.path).readBytes())
  }

  @Test
  fun aDeclaredSizeOverTheCapIsRefusedWithoutOpeningTheContent() {
    val source = FakeDocumentSource("big.txt", declaredBytes = 1001, content = bytes(1001))
    rejected(source, SwiftTUIDocumentImportRejection.TOO_LARGE)
    assertEquals(0, source.openCount)
    assertNothingStored()
  }

  @Test
  fun anUndeclaredSizeOverTheCapStopsOneByteAfterTheCap() {
    // 5 MiB behind a provider that reports no size: the read must stop early.
    val source = FakeDocumentSource("stream.txt", declaredBytes = null, content = bytes(5 * 1024 * 1024))
    rejected(source, SwiftTUIDocumentImportRejection.TOO_LARGE)
    assertEquals(1001L, (source.lastStream as ForwardOnlyStream).bytesRead)
    assertTrue((source.lastStream as ForwardOnlyStream).closed)
    assertNothingStored()
  }

  @Test
  fun aProviderThatUnderReportsItsSizeIsStillCapped() {
    val source = FakeDocumentSource("liar.txt", declaredBytes = 10, content = bytes(4000))
    rejected(source, SwiftTUIDocumentImportRejection.TOO_LARGE)
    assertEquals(1001L, (source.lastStream as ForwardOnlyStream).bytesRead)
    assertNothingStored()
  }

  @Test
  fun aDocumentThatGrewPastItsDeclaredSizeIsKeptWhileUnderTheCap() {
    val content = bytes(900)
    val document = materialized(FakeDocumentSource("grew.txt", declaredBytes = 10, content = content)).document
    assertEquals(900L, document.sizeBytes)
    assertArrayEquals(content, File(document.path).readBytes())
  }

  @Test
  fun aNegativeDeclaredSizeMeansUnknown() {
    val document = materialized(FakeDocumentSource("odd.txt", declaredBytes = -1, content = bytes(12))).document
    assertEquals(12L, document.sizeBytes)
  }

  @Test
  fun aZeroByteCapAdmitsOnlyEmptyDocuments() {
    val none = policy.copy(maxDocumentBytes = 0)
    materialized(FakeDocumentSource("empty.txt", content = ByteArray(0)), none)
    rejected(FakeDocumentSource("one.txt", content = bytes(1)), SwiftTUIDocumentImportRejection.TOO_LARGE, none)
  }

  @Test
  fun theLargestRepresentableCapDoesNotOverflowTheReadBound() {
    val unbounded = SwiftTUIDocumentImportPolicy(
      maxDocumentBytes = Long.MAX_VALUE,
      maxRetainedBytes = Long.MAX_VALUE
    )
    val content = bytes(200_000)
    val document = materialized(FakeDocumentSource("large.txt", content = content), unbounded).document
    assertArrayEquals(content, File(document.path).readBytes())
  }

  // Failures: nothing partial survives

  @Test
  fun aStreamThatEndsEarlyIsNotDeliveredAsATruncatedDocument() {
    val source = FakeDocumentSource("short.txt", declaredBytes = 800, content = bytes(500))
    val outcome = rejected(source, SwiftTUIDocumentImportRejection.IO_FAILURE)
    assertTrue(outcome.detail, outcome.detail.contains("500 of 800"))
    assertNothingStored()
  }

  @Test
  fun aReadFailureMidStreamLeavesNoPartialFile() {
    val source = FakeDocumentSource("broken.txt", declaredBytes = 900, stream = {
      InterruptedStream(bytes(900), afterBytes = 400) { throw IOException("connection reset") }
    })
    val outcome = rejected(source, SwiftTUIDocumentImportRejection.IO_FAILURE)
    assertTrue(outcome.detail, outcome.detail.contains("connection reset"))
    assertTrue((source.lastStream as ForwardOnlyStream).closed)
    assertNothingStored()
  }

  @Test
  fun aProviderThatStopsMakingProgressIsAbandoned() {
    rejected(
      FakeDocumentSource("stalled.txt", stream = { StalledStream() }),
      SwiftTUIDocumentImportRejection.IO_FAILURE
    )
    assertNothingStored()
  }

  @Test
  fun deniedAccessIsReportedWhereverTheProviderRaisesIt() {
    val denied = SecurityException("Permission Denial: reading provider requires a grant")
    rejected(
      FakeDocumentSource(describeFailure = denied),
      SwiftTUIDocumentImportRejection.PERMISSION_DENIED
    )
    // The grant was revoked between the metadata query and the open.
    rejected(
      FakeDocumentSource(content = bytes(10), openFailure = denied),
      SwiftTUIDocumentImportRejection.PERMISSION_DENIED
    )
    // ... or while the content was being read.
    rejected(
      FakeDocumentSource(stream = { InterruptedStream(bytes(900), afterBytes = 300) { throw denied } }),
      SwiftTUIDocumentImportRejection.PERMISSION_DENIED
    )
    assertNothingStored()
  }

  @Test
  fun aMissingDocumentIsReportedAsNotFound() {
    val missing = FileNotFoundException("No such document")
    rejected(FakeDocumentSource(describeFailure = missing), SwiftTUIDocumentImportRejection.NOT_FOUND)
    rejected(
      FakeDocumentSource(content = bytes(10), openFailure = missing),
      SwiftTUIDocumentImportRejection.NOT_FOUND
    )
    assertNothingStored()
  }

  @Test
  fun aProviderCrashIsAnIoFailureNotAnAppCrash() {
    rejected(
      FakeDocumentSource(describeFailure = IllegalStateException("provider died")),
      SwiftTUIDocumentImportRejection.IO_FAILURE
    )
    rejected(
      FakeDocumentSource(content = bytes(10), openFailure = UnsupportedOperationException("virtual")),
      SwiftTUIDocumentImportRejection.IO_FAILURE
    )
    rejected(
      FakeDocumentSource(openFailure = IOException("remote side is dead")),
      SwiftTUIDocumentImportRejection.IO_FAILURE
    )
    assertNothingStored()
  }

  @Test
  fun cancellationMidCopyLeavesNoPartialFile() {
    var cancelled = false
    val source = FakeDocumentSource("slow.txt", stream = {
      InterruptedStream(bytes(900), afterBytes = 300) { cancelled = true }
    })
    try {
      store.materialize(source, policy) { cancelled }
      fail("a cancelled import must not return an outcome")
    } catch (expected: CancellationException) {
      // The caller's coroutine was cancelled.
    }
    assertTrue((source.lastStream as ForwardOnlyStream).closed)
    assertNothingStored()
  }

  @Test
  fun aStorageFailureIsReportedAndLeavesNothing() {
    // The session directory cannot be created: a file sits where it belongs.
    File(fixture.root, "session").writeText("in the way")
    val outcome = rejected(
      FakeDocumentSource(content = bytes(10)),
      SwiftTUIDocumentImportRejection.IO_FAILURE
    )
    assertTrue(outcome.detail, outcome.detail.startsWith("import storage failed"))
    assertEquals(listOf("session"), fixture.files())
    File(fixture.root, "session").delete()
  }

  // Repeated delivery

  @Test
  fun unchangedContentUnderTheSameNameReusesTheRetainedFile() {
    val content = bytes(600)
    val first = materialized(FakeDocumentSource("report.txt", content = content))
    val second = materialized(FakeDocumentSource("report.txt", content = content))

    assertTrue(first.createdFile)
    assertFalse(second.createdFile)
    assertSame(first.document, second.document)
    assertEquals(listOf("session/1/report.txt"), fixture.files())
    assertEquals(600L, store.retainedBytes)
    assertArrayEquals(content, File(first.document.path).readBytes())
  }

  @Test
  fun changedContentUnderTheSameNameIsANewDocument() {
    val first = materialized(FakeDocumentSource("report.txt", content = bytes(600, seed = 1)))
    val second = materialized(FakeDocumentSource("report.txt", content = bytes(600, seed = 2)))
    assertTrue(second.createdFile)
    assertNotEquals(first.document.path, second.document.path)
    assertEquals(1200L, store.retainedBytes)
  }

  @Test
  fun aRetainedFileThatVanishedIsImportedAgain() {
    val content = bytes(100)
    val first = materialized(FakeDocumentSource("gone.txt", content = content))
    assertTrue(File(first.document.path).delete())

    val second = materialized(FakeDocumentSource("gone.txt", content = content))
    assertTrue(second.createdFile)
    assertArrayEquals(content, File(second.document.path).readBytes())
    assertEquals(100L, store.retainedBytes)
  }

  // Retained-bytes budget

  @Test
  fun anImportThatWouldExceedTheBudgetIsRefusedAndEarlierDocumentsSurvive() {
    val first = materialized(FakeDocumentSource("a.txt", content = bytes(1000, seed = 1))).document
    val second = materialized(FakeDocumentSource("b.txt", content = bytes(1000, seed = 2))).document
    // 2000 retained; the budget is 2500.
    rejected(
      FakeDocumentSource("c.txt", content = bytes(501, seed = 3)),
      SwiftTUIDocumentImportRejection.STORAGE_BUDGET_EXCEEDED
    )
    assertEquals(listOf("session/1/a.txt", "session/2/b.txt"), fixture.files())
    assertEquals(2000L, store.retainedBytes)
    assertArrayEquals(bytes(1000, seed = 1), File(first.path).readBytes())
    assertArrayEquals(bytes(1000, seed = 2), File(second.path).readBytes())

    // Exactly filling the budget is allowed, and a repeat costs nothing.
    materialized(FakeDocumentSource("d.txt", content = bytes(500, seed = 4)))
    assertFalse(materialized(FakeDocumentSource("a.txt", content = bytes(1000, seed = 1))).createdFile)
    assertEquals(2500L, store.retainedBytes)
  }

  @Test
  fun clearDeletesTheDocumentsAndFreesTheBudget() {
    materialized(FakeDocumentSource("a.txt", content = bytes(1000, seed = 1)))
    materialized(FakeDocumentSource("b.txt", content = bytes(1000, seed = 2)))

    store.clear()

    assertNothingStored()
    val after = materialized(FakeDocumentSource("c.txt", content = bytes(1000, seed = 3))).document
    assertEquals(File(fixture.root, "session/3/c.txt").absolutePath, after.path)
    assertEquals(1000L, store.retainedBytes)
  }

  @Test
  fun discardRollsBackANewFileButNotAReusedOne() {
    val content = bytes(300)
    val first = materialized(FakeDocumentSource("keep.txt", content = content))
    val repeat = materialized(FakeDocumentSource("keep.txt", content = content))

    store.discard(repeat)
    assertEquals(listOf("session/1/keep.txt"), fixture.files())
    assertEquals(300L, store.retainedBytes)

    store.discard(first)
    assertNothingStored()
    assertEquals(listOf("session"), fixture.directories())
    // Discarding twice is harmless.
    store.discard(first)
    assertEquals(0L, store.retainedBytes)
  }

  // Session lifetime and cleanup

  @Test
  fun withoutASessionNothingIsRead() {
    val idle = fixture.newStore()
    val source = FakeDocumentSource(content = bytes(10))
    val outcome = idle.materialize(source, policy)
    assertEquals(
      SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
      (outcome as SwiftTUIDocumentImportStore.Outcome.Rejected).reason
    )
    assertEquals(0, source.describeCount)
  }

  @Test
  fun endingTheSessionDeletesItsDocuments() {
    val document = materialized(FakeDocumentSource("a.txt", content = bytes(100))).document
    assertTrue(File(document.path).isFile)

    store.endSession()

    assertEquals(emptyList<String>(), fixture.files())
    assertEquals(emptyList<String>(), fixture.directories())
    assertEquals(0L, store.retainedBytes)
  }

  @Test
  fun anImportThatOutlivesItsSessionIsRefusedAndLeavesNothing() {
    // The host is destroyed while the provider is still streaming.
    val source = FakeDocumentSource("late.txt", stream = {
      InterruptedStream(bytes(900), afterBytes = 300) { store.endSession() }
    })
    rejected(source, SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING)
    assertEquals(emptyList<String>(), fixture.files())
  }

  @Test
  fun anImportDoesNotCommitIntoALaterSession() {
    val names = ImportFixture(sessionNames = listOf("first", "second"))
    try {
      val restarted = names.store
      restarted.beginSession()
      val source = FakeDocumentSource("late.txt", stream = {
        InterruptedStream(bytes(900), afterBytes = 300) { restarted.beginSession() }
      })
      val outcome = restarted.materialize(source, policy)
      assertEquals(
        SwiftTUIDocumentImportRejection.HOST_NOT_RUNNING,
        (outcome as SwiftTUIDocumentImportStore.Outcome.Rejected).reason
      )
      assertEquals(emptyList<String>(), names.files())

      // The new session works and its outcome is the current one.
      val next = restarted.materialize(FakeDocumentSource("next.txt", content = bytes(5)), policy)
        as SwiftTUIDocumentImportStore.Outcome.Materialized
      assertEquals(listOf("second/1/next.txt"), names.files())
      assertTrue(restarted.isCurrent(next))
      restarted.beginSession()
      assertFalse(restarted.isCurrent(next))
    } finally {
      names.close()
    }
  }

  @Test
  fun theSweepDeletesWhatADeadProcessLeftButNotLiveSessions() {
    val document = materialized(FakeDocumentSource("live.txt", content = bytes(10))).document
    val other = fixture.newStore().also { it.beginSession() }
    val otherDocument = (other.materialize(FakeDocumentSource("other.txt", content = bytes(10)), policy)
      as SwiftTUIDocumentImportStore.Outcome.Materialized).document

    // Leftovers of a killed process: a whole session with a committed document
    // and an interrupted copy, and a stray file.
    File(fixture.root, "dead-session/3").mkdirs()
    File(fixture.root, "dead-session/3/old.txt").writeText("stale")
    File(fixture.root, "dead-session/4.partial").writeText("half")
    File(fixture.root, "stray.tmp").writeText("stray")

    store.sweepStaleSessions()

    assertEquals(
      listOf(File(document.path), File(otherDocument.path)).map { it.relativeTo(fixture.root).path }.sorted(),
      fixture.files()
    )
    assertFalse(File(fixture.root, "dead-session").exists())
    other.endSession()
  }

  @Test
  fun theSweepToleratesAMissingRoot() {
    fixture.root.deleteRecursively()
    store.sweepStaleSessions()
    assertFalse(fixture.root.exists())
  }

  @Test
  fun deletionNeverFollowsASymbolicLinkOutOfTheStore() {
    val outside = Files.createTempDirectory("swifttui-outside").toFile()
    try {
      val precious = File(outside, "precious.txt").apply { writeText("keep me") }
      File(fixture.root, "dead-session").mkdirs()
      Files.createSymbolicLink(File(fixture.root, "dead-session/link").toPath(), outside.toPath())

      store.sweepStaleSessions()

      assertFalse(File(fixture.root, "dead-session").exists())
      assertEquals("keep me", precious.readText())
    } finally {
      outside.deleteRecursively()
    }
  }
}
