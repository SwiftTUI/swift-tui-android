package sh.swifttui.android.host

/** Geometry shared by the native Canvas call and its coordinate-space tests. */
internal data class SwiftTUIImagePlacement(
  val destination: SwiftTUIRect,
  val clip: SwiftTUIRect
) {
  companion object {
    fun forAttachment(attachment: SwiftTUIImageAttachment): SwiftTUIImagePlacement? {
      val bounds = attachment.bounds
      val visible = attachment.visibleBounds
      if (bounds.width <= 0 || bounds.height <= 0 || visible.width <= 0 || visible.height <= 0) {
        return null
      }
      // A clipped image keeps its full placement transform. Blended wire
      // payloads already carry cropped bounds == visibleBounds.
      return SwiftTUIImagePlacement(destination = bounds, clip = visible)
    }
  }
}
