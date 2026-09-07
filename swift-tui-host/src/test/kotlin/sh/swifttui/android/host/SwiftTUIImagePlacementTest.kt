package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwiftTUIImagePlacementTest {
  private fun attachment(bounds: String, visible: String): SwiftTUIImageAttachment {
    val frame = SwiftTUIWebSurfaceSession().decode(SwiftTUIWebSurfaceSession.RECORD_PREFIX +
      """{"version":2,"width":8,"height":4,"styles":[null],"rows":[[],[],[],[]],"images":[{"id":"png:crop","format":"png","bounds":$bounds,"visibleBounds":$visible,"scalingMode":"stretch","dataBase64":"QQ=="}]}"""
    )!!
    return frame.imageAttachments.single()
  }

  @Test
  fun clippedWireImageKeepsItsOriginalSourceTransform() {
    val placement = SwiftTUIImagePlacement.forAttachment(
      attachment("[-2,1,4,2]", "[0,1,2,2]")
    )!!
    assertEquals(SwiftTUIRect(-2, 1, 4, 2), placement.destination)
    assertEquals(SwiftTUIRect(0, 1, 2, 2), placement.clip)
    // A four-pixel source [red, red, blue, blue] is clipped at the left.
    // The center of the first visible cell must still sample the blue half,
    // not the red pixel produced by shrinking the full source into the clip.
    val visibleCellCenter = 0.5
    val sourceX = (visibleCellCenter - placement.destination.x) / placement.destination.width * 4
    assertEquals(2.5, sourceX, 0.0)
  }

  @Test
  fun precompositedWireImageAlreadyUsesTheVisibleDestination() {
    val placement = SwiftTUIImagePlacement.forAttachment(
      attachment("[0,1,2,2]", "[0,1,2,2]")
    )!!
    assertEquals(placement.destination, placement.clip)
  }

  @Test
  fun nonpositivePlacementOrVisibilitySkipsImageLookup() {
    assertNull(SwiftTUIImagePlacement.forAttachment(attachment("[0,0,0,2]", "[0,0,2,2]")))
    assertNull(SwiftTUIImagePlacement.forAttachment(attachment("[0,0,2,2]", "[0,0,2,0]")))
  }

  @Test
  fun imageDestinationAndClipEndpointsCannotWrapBeforePixelConversion() {
    val placement = SwiftTUIImagePlacement.forAttachment(
      attachment("[1,1,2147483647,2147483647]", "[2147483646,2147483646,3,3]")
    )!!
    for (rect in listOf(placement.destination, placement.clip)) {
      // Both neighboring endpoints round to these representable Float pixels.
      assertEquals(4_294_967_296f, SwiftTUIRasterClipGeometry.pixelEnd(rect.x, rect.width, 2f), 0f)
      assertEquals(6_442_450_944f, SwiftTUIRasterClipGeometry.pixelEnd(rect.y, rect.height, 3f), 0f)
    }
    // Converting both operands to Float before adding would lose this -1.
    assertEquals(-1f, SwiftTUIRasterClipGeometry.pixelEnd(Int.MIN_VALUE, Int.MAX_VALUE, 1f), 0f)
  }
}
