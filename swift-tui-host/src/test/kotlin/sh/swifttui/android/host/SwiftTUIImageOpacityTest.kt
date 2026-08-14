package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

class SwiftTUIImageOpacityTest {
  @Test
  fun imagePaintAlphaClampsEffectiveOpacity() {
    assertEquals(255, swiftTUIImageAlpha(Double.NaN))
    assertEquals(0, swiftTUIImageAlpha(-1.0))
    assertEquals(102, swiftTUIImageAlpha(0.4))
    assertEquals(255, swiftTUIImageAlpha(2.0))
  }

  @Test
  fun alphaIsPlacementStateNotBitmapCacheIdentity() {
    val opaque = attachment(opacity = 1.0)
    val faded = attachment(opacity = 0.25)

    assertEquals(swiftTUIImageCacheKey(opaque), swiftTUIImageCacheKey(faded))
  }

  private fun attachment(opacity: Double) = SwiftTUIImageAttachment(
    id = "png:content:4",
    bounds = SwiftTUIRect(0, 0, 1, 1),
    visibleBounds = SwiftTUIRect(0, 0, 1, 1),
    sourceKind = "webSurface",
    sourceIdentifier = null,
    payloadBase64 = null,
    payloadByteCount = 4,
    pixelSize = SwiftTUIPixelSize(1, 1),
    cellPixelSize = null,
    isResizable = false,
    scalingMode = "stretch",
    opacity = opacity
  )
}
