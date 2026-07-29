package sh.swifttui.android.host

internal object SwiftTUIBitmapCachePolicy {
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
