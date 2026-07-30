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
      "${SwiftTUIWebSurfaceSession.SUPPORTED_WEB_SURFACE_VERSION >= 3}," +
      """"styleAppend":$STYLE_APPEND}"""

  /**
   * Declared because [SwiftTUIWebSurfaceSession] splices a delta's `styles`
   * onto its retained table when `stylesBase` is present. Truthful by
   * construction in the same sense as delta acceptance: the decoder that
   * declares it is the decoder that ships with it. It replaces a full style
   * retransmit measured at 69.7% of late-record bytes in a style-churning
   * epoch.
   */
  private const val STYLE_APPEND = true
}
