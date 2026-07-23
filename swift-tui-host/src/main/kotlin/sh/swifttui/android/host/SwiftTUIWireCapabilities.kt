package sh.swifttui.android.host

/**
 * The host's wire-capability declaration, delivered to the Swift host before
 * the scene starts (`SwiftTUIHostState.start`). Truthful by construction:
 * the ceiling tracks [SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION]
 * and delta acceptance follows v3 support, so landing a newer decoder
 * automatically declares it. The converged web-surface wire is the only
 * frame format (the legacy keyed-JSON wire retired in Stage C4); the
 * declaration negotiates the record-shape ceiling and delta.
 */
internal object SwiftTUIWireCapabilities {
  fun declarationJson(): String =
    """{"acceptsDeltaFrames":""" +
      "${SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION >= 3}," +
      """"maxWebSurfaceVersion":${SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION}}"""
}
