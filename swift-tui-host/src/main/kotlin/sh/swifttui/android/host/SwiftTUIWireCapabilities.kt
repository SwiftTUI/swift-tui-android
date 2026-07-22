package sh.swifttui.android.host

/**
 * The host's wire-capability declaration, delivered to the Swift host before
 * the scene starts (`SwiftTUIHostState.start`). Truthful by construction:
 * the schema ceiling tracks [SwiftTUIFrame.SUPPORTED_SCHEMA_VERSION], so
 * landing decoder support for a newer schema automatically declares it.
 * Absence of a declaration keeps the Swift-side defaults — today's wire
 * bytes — and Swift host libraries that predate the entry point ignore it
 * (the JNI glue resolves the symbol lazily and no-ops when it is missing).
 */
internal object SwiftTUIWireCapabilities {
  fun declarationJson(): String =
    """{"maxAndroidSchemaVersion":${SwiftTUIFrame.SUPPORTED_SCHEMA_VERSION}}"""
}
