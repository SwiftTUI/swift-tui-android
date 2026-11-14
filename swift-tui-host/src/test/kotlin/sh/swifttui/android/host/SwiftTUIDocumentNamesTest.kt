package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTUIDocumentNamesTest {
  @Test
  fun ordinaryNamesSurviveUnchanged() {
    for (name in listOf("notes.txt", "My Report (final).csv", "it's 写真 100%.png", "naïve café.json")) {
      assertEquals(name, SwiftTUIDocumentNames.sanitized(name))
    }
  }

  @Test
  fun onlyTheLastPathComponentIsKept() {
    assertEquals("passwd", SwiftTUIDocumentNames.sanitized("../../etc/passwd"))
    assertEquals("evil.txt", SwiftTUIDocumentNames.sanitized("/data/data/other.app/files/evil.txt"))
    assertEquals("evil.txt", SwiftTUIDocumentNames.sanitized("..\\..\\evil.txt"))
    assertEquals("b.txt", SwiftTUIDocumentNames.sanitized("a/b.txt"))
  }

  @Test
  fun namesThatAreNotAFileNameFallBack() {
    for (name in listOf(null, "", "   ", ".", "..", "...", "/", "a/", "\u0000\u0001", " . ")) {
      assertEquals(
        "sanitized($name)",
        SwiftTUIDocumentNames.FALLBACK_NAME,
        SwiftTUIDocumentNames.sanitized(name)
      )
    }
  }

  @Test
  fun leadingDotsAreDroppedSoNothingIsHidden() {
    assertEquals("bashrc", SwiftTUIDocumentNames.sanitized(".bashrc"))
    assertEquals("env.txt", SwiftTUIDocumentNames.sanitized("..env.txt"))
  }

  @Test
  fun controlAndDirectionOverrideCharactersAreRemoved() {
    assertEquals("ab.txt", SwiftTUIDocumentNames.sanitized("a\u0000b\u001B.txt"))
    assertEquals("line.txt", SwiftTUIDocumentNames.sanitized("li\nne\r.t\tx\u007Ft"))
    // U+202E would display "gnp.exe" as "exe.png".
    assertEquals("photogpj.exe", SwiftTUIDocumentNames.sanitized("photo‮gpj.exe"))
    assertEquals("ab", SwiftTUIDocumentNames.sanitized("a  ⁦⁩b"))
  }

  @Test
  fun unpairedSurrogatesAreRemovedAndPairsKept() {
    assertEquals("ab.txt", SwiftTUIDocumentNames.sanitized("a\uD83Db.txt"))
    assertEquals("ab.txt", SwiftTUIDocumentNames.sanitized("a\uDE00b.txt"))
    assertEquals("a😀b.txt", SwiftTUIDocumentNames.sanitized("a😀b.txt"))
  }

  @Test
  fun longNamesAreCappedInBytesAndKeepTheirExtension() {
    val ascii = SwiftTUIDocumentNames.sanitized("a".repeat(400) + ".png")
    assertEquals(SwiftTUIDocumentNames.MAX_NAME_BYTES, ascii.encodeToByteArray().size)
    assertTrue(ascii.endsWith(".png"))

    // Four-byte characters: the cut lands on a character boundary.
    val emoji = SwiftTUIDocumentNames.sanitized("😀".repeat(100) + ".jpeg")
    assertTrue(emoji.encodeToByteArray().size <= SwiftTUIDocumentNames.MAX_NAME_BYTES)
    assertTrue(emoji.endsWith(".jpeg"))
    assertEquals(emoji, String(emoji.encodeToByteArray(), Charsets.UTF_8))
    assertEquals("😀".repeat(28) + ".jpeg", emoji)

    val exact = "b".repeat(SwiftTUIDocumentNames.MAX_NAME_BYTES - 4) + ".txt"
    assertEquals(exact, SwiftTUIDocumentNames.sanitized(exact))

    val noExtension = SwiftTUIDocumentNames.sanitized("c".repeat(500))
    assertEquals("c".repeat(SwiftTUIDocumentNames.MAX_NAME_BYTES), noExtension)
  }

  @Test
  fun extensionIsTheLowercasedPlausibleSuffix() {
    assertEquals("png", SwiftTUIDocumentNames.extension("photo.PNG"))
    assertEquals("gz", SwiftTUIDocumentNames.extension("archive.tar.gz"))
    assertNull(SwiftTUIDocumentNames.extension("README"))
    assertNull(SwiftTUIDocumentNames.extension("trailing."))
    assertNull(SwiftTUIDocumentNames.extension(".hidden"))
    assertNull(SwiftTUIDocumentNames.extension("version 1.2 final"))
    assertNull(SwiftTUIDocumentNames.extension("name." + "x".repeat(17)))
  }

  @Test
  fun mimeTypesAreNormalizedBeforeMatching() {
    assertEquals("text/plain", SwiftTUIDocumentNames.normalizedMimeType(" Text/Plain; charset=UTF-8 "))
    assertNull(SwiftTUIDocumentNames.normalizedMimeType(null))
    assertNull(SwiftTUIDocumentNames.normalizedMimeType("  "))
    assertNull(SwiftTUIDocumentNames.normalizedMimeType("; charset=utf-8"))
  }

  @Test
  fun allowListEntriesMatchExactlyOrByWildcard() {
    assertTrue(SwiftTUIDocumentNames.mimeTypeMatches("image/png", "image/png"))
    assertTrue(SwiftTUIDocumentNames.mimeTypeMatches("IMAGE/PNG", "image/png"))
    assertFalse(SwiftTUIDocumentNames.mimeTypeMatches("image/png", "image/jpeg"))
    assertFalse(SwiftTUIDocumentNames.mimeTypeMatches("image/png", "image/png2"))

    assertTrue(SwiftTUIDocumentNames.mimeTypeMatches("text/*", "text/csv"))
    assertFalse(SwiftTUIDocumentNames.mimeTypeMatches("text/*", "text/"))
    assertFalse(SwiftTUIDocumentNames.mimeTypeMatches("text/*", "texture/png"))
    assertFalse(SwiftTUIDocumentNames.mimeTypeMatches("text/*", "application/text"))

    assertTrue(SwiftTUIDocumentNames.mimeTypeMatches("*/*", "application/octet-stream"))
    assertTrue(SwiftTUIDocumentNames.mimeTypeMatches("*", "video/mp4"))
    assertFalse(SwiftTUIDocumentNames.mimeTypeMatches("", "text/plain"))
  }

  @Test
  fun defaultPolicyAcceptsTextAndTheRasterFormatsTheFrameworkDecodes() {
    val policy = SwiftTUIDocumentImportPolicy()
    fun accepts(type: String) =
      policy.acceptedMimeTypes.any { SwiftTUIDocumentNames.mimeTypeMatches(it, type) }
    for (type in listOf("text/plain", "text/csv", "text/markdown", "application/json",
      "image/png", "image/jpeg", "image/gif")) {
      assertTrue(type, accepts(type))
    }
    for (type in listOf("application/octet-stream", "application/pdf", "image/webp",
      "video/mp4", "application/zip", "application/vnd.android.package-archive")) {
      assertFalse(type, accepts(type))
    }
    assertEquals(16L * 1024 * 1024, policy.maxDocumentBytes)
    assertEquals(64L * 1024 * 1024, policy.maxRetainedBytes)
  }

  @Test(expected = IllegalArgumentException::class)
  fun aNegativeDocumentCapIsRefused() {
    SwiftTUIDocumentImportPolicy(maxDocumentBytes = -1)
  }

  @Test(expected = IllegalArgumentException::class)
  fun aNegativeRetainedBudgetIsRefused() {
    SwiftTUIDocumentImportPolicy(maxRetainedBytes = -1)
  }
}
