package sh.swifttui.android.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SwiftTUIWireCapabilitiesTest {
  @Test
  fun declarationTracksTheSupportedSchemaVersion() {
    // The ceiling must follow the decoder's supported version so landing a
    // newer-schema decoder automatically declares it — a hardcoded literal
    // here would silently under-declare.
    val declaration = JSONObject(SwiftTUIWireCapabilities.declarationJson())

    assertEquals(3, declaration.length())
    assertEquals(
      SwiftTUIFrame.SUPPORTED_SCHEMA_VERSION,
      declaration.getInt("maxAndroidSchemaVersion")
    )
    // The converged web-surface ceiling tracks the decoder session's
    // supported version, and delta acceptance follows v3 support.
    assertEquals(
      SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION,
      declaration.getInt("maxWebSurfaceVersion")
    )
    assertEquals(
      SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION >= 3,
      declaration.getBoolean("acceptsDeltaFrames")
    )
  }
}
