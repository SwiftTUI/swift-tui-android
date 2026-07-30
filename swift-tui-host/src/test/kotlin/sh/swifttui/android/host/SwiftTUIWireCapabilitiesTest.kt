package sh.swifttui.android.host

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SwiftTUIWireCapabilitiesTest {
  @Test
  fun declarationTracksTheSupportedSchemaVersion() {
    // Delta acceptance must follow the decoder's supported version so landing
    // a newer decoder automatically declares it — a hardcoded literal here
    // would silently under-declare.
    val declaration = JSONObject(SwiftTUIWireCapabilities.declarationJson())

    // Capabilities are named feature bits, and the census is exact: an
    // undeclared bit silently keeps the old record shape, and a declared one
    // this decoder cannot honour corrupts frames. `maxWebSurfaceVersion`
    // retired alongside `maxAndroidSchemaVersion` (Stage C4) — it declared a
    // ceiling the encoder only read as "accepts delta or not", duplicating the
    // version check this decoder already performs on every record.
    assertEquals(2, declaration.length())
    assertEquals(
      SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION >= 3,
      declaration.getBoolean("acceptsDeltaFrames")
    )
    // Declared because `decodeDelta` splices an appended style table onto its
    // retained one when `stylesBase` is present.
    assertEquals(true, declaration.getBoolean("styleAppend"))
  }
}
