package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ONE_MIB = 1024 * 1024

class SwiftTUIBitmapCachePolicyTest {
  private data class Entry(
    val size: Int,
    var recycled: Boolean = false
  )

  private data class PressureResult(
    val evictionCount: Int,
    val recycledReturnCount: Int
  )

  private class RecyclingCache(
    private val maxSize: Int
  ) {
    private val entries = linkedMapOf<String, Entry>()
    private var size = 0

    var evictionCount = 0
      private set

    fun put(key: String, entry: Entry) {
      entries.put(key, entry)?.let { replaced ->
        size -= replaced.size
        replaced.recycled = true
      }
      size += entry.size

      while (size > maxSize) {
        val eldest = entries.entries.first()
        entries.remove(eldest.key)
        size -= eldest.value.size
        eldest.value.recycled = true
        evictionCount += 1
      }
    }
  }

  private fun runCachePressure(
    maxSize: Int,
    cacheOrReturn: (String, Entry, RecyclingCache) -> Entry
  ): PressureResult {
    val cache = RecyclingCache(maxSize)
    var recycledReturnCount = 0

    repeat(12) { index ->
      val entry = Entry(
        size = if (index == 6) maxSize + 1 else ONE_MIB
      )
      val returned = cacheOrReturn("image-$index", entry, cache)
      if (returned.recycled) {
        recycledReturnCount += 1
      }
    }

    return PressureResult(
      evictionCount = cache.evictionCount,
      recycledReturnCount = recycledReturnCount
    )
  }

  private fun alwaysPutThenReturn(
    key: String,
    entry: Entry,
    cache: RecyclingCache
  ): Entry {
    cache.put(key, entry)
    return entry
  }

  @Test
  fun oversizedEntryIsReturnedUncachedAndLive() {
    val insertedKeys = mutableListOf<String>()
    val entry = Entry(size = 8)

    val returned = SwiftTUIBitmapCachePolicy.cacheOrReturn(
      key = "oversized",
      value = entry,
      maxSize = { 8 },
      sizeOf = { it.size },
      put = { key, inserted ->
        insertedKeys += key
        inserted.recycled = true
      }
    )

    assertSame(entry, returned)
    assertTrue(insertedKeys.isEmpty())
    assertFalse(entry.recycled)
  }

  @Test
  fun cachePressureDistinguishesAlwaysPutBaselineFromGuardedPolicy() {
    for (maxSize in listOf(4 * ONE_MIB, 8 * ONE_MIB)) {
      val baseline = runCachePressure(maxSize, ::alwaysPutThenReturn)
      val guarded = runCachePressure(maxSize) { key, entry, cache ->
        SwiftTUIBitmapCachePolicy.cacheOrReturn(
          key = key,
          value = entry,
          maxSize = { maxSize },
          sizeOf = { it.size },
          put = cache::put
        )
      }

      assertTrue(baseline.evictionCount > 0)
      assertTrue(baseline.recycledReturnCount > 0)
      assertTrue(guarded.evictionCount > 0)
      assertEquals(0, guarded.recycledReturnCount)
    }
  }
}
