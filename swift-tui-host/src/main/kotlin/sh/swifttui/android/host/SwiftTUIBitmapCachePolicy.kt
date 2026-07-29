package sh.swifttui.android.host

internal sealed interface SwiftTUIBitmapResolution<out Value> {
  data class Drawable<Value>(val value: Value) : SwiftTUIBitmapResolution<Value>
  data object MissingPayload : SwiftTUIBitmapResolution<Nothing>
  data object InvalidPayload : SwiftTUIBitmapResolution<Nothing>
}

internal object SwiftTUIBitmapCachePolicy {
  /**
   * Resolves one attachment without conflating two materially different
   * failures: an omitted transmit-once payload means the consumer forgot an
   * image and may request it again, while a present but invalid payload is a
   * decode failure and must not start a missing-payload request loop.
   */
  fun <Key, Payload, Value> resolve(
    key: Key,
    cached: () -> Value?,
    payload: Payload?,
    decode: (Payload) -> Value?,
    maxSize: () -> Int,
    sizeOf: (Value) -> Int,
    put: (Key, Value) -> Unit
  ): SwiftTUIBitmapResolution<Value> {
    cached()?.let {
      return SwiftTUIBitmapResolution.Drawable(it)
    }
    val encoded = payload ?: return SwiftTUIBitmapResolution.MissingPayload
    val decoded = decode(encoded) ?: return SwiftTUIBitmapResolution.InvalidPayload
    return SwiftTUIBitmapResolution.Drawable(
      cacheOrReturn(
        key = key,
        value = decoded,
        maxSize = maxSize,
        sizeOf = sizeOf,
        put = put
      )
    )
  }

  fun <Key, Value> cacheOrReturn(
    key: Key,
    value: Value,
    maxSize: () -> Int,
    sizeOf: (Value) -> Int,
    put: (Key, Value) -> Unit
  ): Value {
    if (sizeOf(value) < maxSize()) {
      put(key, value)
    }
    return value
  }
}
