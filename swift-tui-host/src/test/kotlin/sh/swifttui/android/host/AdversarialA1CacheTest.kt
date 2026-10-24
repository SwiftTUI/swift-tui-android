package sh.swifttui.android.host

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Finite contract cases. The coordination-root A1 inventory owns case descriptions. */
@RunWith(Parameterized::class)
class AdversarialA1CacheTest(private val id: String, private val scenario: Int) {
  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun cases(): List<Array<Any>> = (56..65).map { arrayOf("a1s%03d".format(it), it) }
  }

  @Test
  fun contract() {
    data class Entry(val size: Int)
    val value = Entry(when (scenario) { 60 -> 3; 61 -> 4; 62 -> 5; 63 -> 0; else -> 1 })
    var decodes = 0
    val puts = mutableListOf<Pair<String, Entry>>()
    val result = SwiftTUIBitmapCachePolicy.resolve(
      key = if (scenario == 64) "other-entry" else "entry", cached = { if (scenario in 56..57) value else null },
      payload = if (scenario == 56 || scenario == 58) null else "payload",
      decode = { _: String -> decodes++; if (scenario == 59) null else value },
      maxSize = { if (scenario == 63) 0 else 4 }, sizeOf = { it.size },
      put = { key, entry -> puts += key to entry }
    )
    if (scenario == 65) {
      val second = SwiftTUIBitmapCachePolicy.resolve(
        key = "entry", cached = { puts.single().second }, payload = "payload",
        decode = { _: String -> decodes++; value }, maxSize = { 4 }, sizeOf = { it.size },
        put = { key, entry -> puts += key to entry }
      )
      assertSame(value, (second as SwiftTUIBitmapResolution.Drawable<*>).value)
    }
    when (scenario) {
      58 -> assertSame(SwiftTUIBitmapResolution.MissingPayload, result)
      59 -> assertSame(SwiftTUIBitmapResolution.InvalidPayload, result)
      else -> assertSame(value, (result as SwiftTUIBitmapResolution.Drawable<*>).value)
    }
    assertEquals(if (scenario in 56..58) 0 else 1, decodes)
    assertEquals(if (scenario == 60 || scenario in 64..65) 1 else 0, puts.size)
    if (puts.isNotEmpty()) {
      assertEquals(if (scenario == 64) "other-entry" else "entry", puts.single().first)
      assertSame(value, puts.single().second)
    }
  }
}
