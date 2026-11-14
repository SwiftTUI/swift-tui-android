import org.gradle.api.InvalidUserDataException

/**
 * One Android ABI the `sh.swifttui.android` plugin can cross-build and stage.
 *
 * This is the single table that maps an Android ABI name to everything that
 * differs per ABI: the Swift SDK triple, SwiftPM's scratch and output
 * directories, the Swift runtime directory inside the Swift Android SDK bundle,
 * and the NDK's `libc++_shared.so`. Nothing else in the plugin may spell an
 * architecture name.
 *
 * The entries are exactly the ABIs the host AAR ships a JNI shim for
 * (`swift-tui-host/build.gradle.kts` `abiFilters` and
 * `swift-tui-host/src/main/jni/Application.mk` `APP_ABI`); a unit test holds
 * the three lists together. An ABI the Swift Android SDK can target but the
 * AAR has no shim for would stage a host library nothing can load.
 */
internal enum class SwiftTuiAndroidAbi(
  /** The Android ABI name: the `jniLibs/<abi>` and APK `lib/<abi>` directory. */
  val abiName: String,
  /** The architecture as the Swift Android SDK spells it. */
  val swiftArchitecture: String,
  /** The NDK sysroot library directory for this ABI. */
  val ndkTriple: String,
) {
  ARM64_V8A("arm64-v8a", "aarch64", "aarch64-linux-android"),
  X86_64("x86_64", "x86_64", "x86_64-linux-android");

  /** The `--swift-sdk` target triple, at the host's `minSdk`. */
  val swiftSdkTriple: String
    get() = "$swiftArchitecture-unknown-linux-android$MIN_API_LEVEL"

  /**
   * SwiftPM's scratch directory for this ABI, relative to the package, or
   * `null` for SwiftPM's own default (`.build`).
   *
   * Two triples must not share a scratch directory. SwiftPM keeps one build
   * manifest and one build database per scratch directory (`debug.yaml`,
   * `build.db`) but one cached build description per triple inside it. After
   * the first build of a second triple the manifest belongs to that triple,
   * and the next build of the first one pairs its own cached description with
   * the other triple's manifest and fails with `No target named
   * '<product>-<triple>-debug.dylib' in build description` — on every build,
   * until something else forces a re-plan.
   *
   * The default ABI keeps SwiftPM's default, so an app that selects nothing
   * runs exactly the `swift build` command it always did, in the same
   * directory. Every other ABI gets its own scratch directory nested in
   * `.build`, which consumers already ignore.
   */
  val swiftScratchSubpath: String?
    get() = if (this == DEFAULT) null else ".build/android-$abiName"

  /** SwiftPM's product directory for this ABI, relative to the package. */
  val swiftBuildSubpath: String
    get() = "${swiftScratchSubpath ?: ".build"}/$swiftSdkTriple/debug"

  /** The Swift runtime `.so` directory, relative to the SDK's `swift-android`. */
  val swiftRuntimeSubpath: String
    get() = "swift-resources/usr/lib/swift-$swiftArchitecture/android"

  /** The NDK's `libc++_shared.so` for this ABI, relative to the NDK root. */
  fun ndkCxxSharedSubpath(ndkHostTag: String): String =
    "toolchains/llvm/prebuilt/$ndkHostTag/sysroot/usr/lib/$ndkTriple/libc++_shared.so"

  /**
   * Suffix for this ABI's task names: `buildSwiftAndroidArm64V8a`,
   * `buildSwiftAndroidX86_64`. Hyphens become word breaks; the underscore in
   * `x86_64` is kept, since `X8664` reads as a different number.
   */
  val taskSuffix: String
    get() = abiName
      .split('-')
      .joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }

  companion object {
    /** The API level in every Swift SDK triple; matches the AAR's `minSdk`. */
    const val MIN_API_LEVEL = 28

    /** The ABI staged when a consumer selects nothing. */
    val DEFAULT = ARM64_V8A

    val supportedNames: List<String>
      get() = entries.map { it.abiName }

    /**
     * Resolves the consumer's `swiftTuiAndroidHost.abis` selection, in this
     * enum's order whatever order the consumer wrote, so task wiring does not
     * depend on set iteration order.
     *
     * Rejects an empty selection and any name the plugin has no complete
     * mapping for, instead of ignoring it: an ignored ABI is an APK that
     * installs on that device and shows a blank view.
     */
    fun resolve(selection: Iterable<String>): List<SwiftTuiAndroidAbi> {
      val requested = selection.toList()
      if (requested.isEmpty()) {
        throw InvalidUserDataException(
          "SwiftTUI: swiftTuiAndroidHost.abis is empty. Select at least one of: " +
            "${supportedNames.joinToString(", ")}."
        )
      }

      val unsupported = requested.filter { name -> entries.none { it.abiName == name } }
      if (unsupported.isNotEmpty()) {
        throw InvalidUserDataException(
          """
          SwiftTUI: swiftTuiAndroidHost.abis names an Android ABI this plugin cannot package: ${unsupported.joinToString(", ")}.

            supported: ${supportedNames.joinToString(", ")}

          Each supported ABI needs a Swift Android SDK triple, a Swift runtime, an
          NDK libc++ and a JNI shim in the sh.swifttui:android-host AAR. The names
          are Android's ABI names, as in android.defaultConfig.ndk.abiFilters.
          """.trimIndent()
        )
      }

      return entries.filter { it.abiName in requested }
    }
  }
}
