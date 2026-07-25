package sh.swifttui.android.host

/**
 * The host's wire-capability declaration, delivered to the Swift host before
 * the scene starts (`SwiftTUIHostState.start`). Truthful by construction:
 * delta acceptance follows this decoder's support for the v3 record shape
 * ([SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION]), so landing a
 * newer decoder automatically declares it. The converged web-surface wire is
 * the only frame format (the legacy keyed-JSON wire retired in Stage C4); the
 * declaration chooses full or delta records.
 *
 * Capabilities are named feature bits, not a version ceiling. The retired
 * `maxWebSurfaceVersion` key declared a decoder ceiling the encoder only ever
 * read as "accepts delta or not", duplicating — more weakly — the version
 * check [SwiftTUIWebSurfaceSession] already performs on every record. Hosts
 * still expecting the key skip unknown keys, so dropping it is safe in both
 * directions.
 */
internal object SwiftTUIWireCapabilities {
  fun declarationJson(): String =
    """{"acceptsDeltaFrames":""" +
      "${SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION >= 3}}"
}
