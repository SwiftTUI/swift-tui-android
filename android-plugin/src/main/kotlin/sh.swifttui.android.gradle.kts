import java.io.File
import java.nio.file.Files

// SwiftTUI Android convention plugin. Owns the per-app Swift -> arm64 `.so`
// cross-build, the Swift-SDK search path, the dev dependency mirror, and the
// jniLibs copy (renaming the product to the canonical host library name). The
// app applies `id("sh.swifttui.android")` instead of pasting these tasks.

val hostConfig = extensions.create("swiftTuiAndroidHost", SwiftTuiAndroidHostExtension::class.java)
hostConfig.hostLibraryName.convention("swift_tui_app_host")
hostConfig.packageDirectory.convention(layout.projectDirectory.dir("../SwiftPackage"))

// Coordination-only, and deliberately opt-in: unset is the normal case, and the
// mirror step is then skipped entirely.
//
// This previously defaulted to `../../../swift-tui`, which made a dependency
// substitution conditional on a relative path *outside* the consumer's project.
// That path resolves to a sibling of the app's own repository, so a consumer who
// happened to have a swift-tui clone there would silently build against it
// instead of the tagged version their Package.swift declares — the same class of
// silent-wrong-output failure the jniLibs check below exists to prevent.
//
// The SwiftTUI org root supplies SWIFTTUI_LOCAL_CHECKOUT when integrating against
// an untagged sibling checkout. Keeping it in the environment rather than in the
// example apps' build files also keeps pre-tag overrides out of the public child
// repositories, where they are not permitted.
hostConfig.swiftTuiCheckout.convention(
  layout.dir(providers.environmentVariable("SWIFTTUI_LOCAL_CHECKOUT").map { File(it) })
)

val swiftSdkName = "aarch64-unknown-linux-android28"
val swiftToolchainVersion = "+6.3.3"
val swiftSdkArtifactName = "swift-6.3.3-RELEASE_android"
val swiftTuiDependencyUrl = "https://github.com/SwiftTUI/swift-tui.git"
val swiftBuildSubpath = ".build/$swiftSdkName/debug"
val generatedJniLibsDir = layout.buildDirectory.dir("generated/swiftJniLibs")
val generatedSwiftSdksDir = layout.buildDirectory.dir("swift-sdks")

val userHome = providers.systemProperty("user.home").get()

// SwiftPM's per-user data directory, where `swift sdk install` unpacks bundles.
// Apple platforms use the Library convention; everywhere else SwiftPM uses a
// dotfile directory. Resolved here rather than hardcoded so a Linux host gets a
// usable default instead of a macOS path that cannot exist (`ndkHostTag` below
// already treats linux and windows as supported build hosts).
val swiftSdksDir = when {
  providers.systemProperty("os.name").get().lowercase().contains("mac") ->
    "$userHome/Library/org.swift.swiftpm/swift-sdks"
  else -> "$userHome/.swiftpm/swift-sdks"
}

val defaultSwiftSdkBundleDir = "$swiftSdksDir/$swiftSdkArtifactName.artifactbundle"
val swiftSdkBundleDir = providers.environmentVariable("SWIFT_ANDROID_SDK_BUNDLE")
  .orElse(defaultSwiftSdkBundleDir)
val defaultSwiftAndroidRoot = swiftSdkBundleDir.map { "$it/swift-android" }
val defaultAndroidNdkDir =
  "$swiftSdksDir/swift-6.3-RELEASE_android.artifactbundle/swift-android/android-ndk-r27d"
val swiftAndroidRoot = providers.environmentVariable("SWIFT_ANDROID_ROOT")
  .orElse(defaultSwiftAndroidRoot)
val swiftAndroidNdkDir = providers.environmentVariable("ANDROID_NDK_HOME")
  .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
  .orElse(defaultAndroidNdkDir)
val swiftRuntimeLibDir = swiftAndroidRoot.map {
  // java.io.File (not Gradle's `file()`), so this provider does not capture the
  // enclosing script object — required for configuration-cache compatibility.
  File("$it/swift-resources/usr/lib/swift-aarch64/android")
}
val ndkHostTag = providers.provider {
  val osName = System.getProperty("os.name").lowercase()
  when {
    osName.contains("mac") -> "darwin-x86_64"
    osName.contains("linux") -> "linux-x86_64"
    osName.contains("windows") -> "windows-x86_64"
    else -> error("Unsupported NDK host OS: ${System.getProperty("os.name")}")
  }
}
val ndkCxxSharedLib = swiftAndroidNdkDir.zip(ndkHostTag) { ndkDir, hostTag ->
  File("$ndkDir/toolchains/llvm/prebuilt/$hostTag/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
}

// True only when both the Android NDK and the Swift Android SDK bundle are
// present. When false the native Swift build is skipped so a JVM-only
// `testDebugUnitTest` gate can configure and run without them.
val swiftAndroidToolingAvailable = providers.provider {
  File(swiftAndroidNdkDir.get()).exists() && File(swiftSdkBundleDir.get()).exists()
}

fun swiftPackageMirrorUrl(checkout: File): String {
  val dotGit = checkout.resolve(".git")
  if (!dotGit.isFile) {
    return checkout.canonicalFile.toPath().toUri().toString()
  }

  val marker = "gitdir:"
  val pointer = dotGit.readText().trim()
  if (!pointer.startsWith(marker)) {
    return checkout.canonicalFile.toPath().toUri().toString()
  }

  val gitDir = pointer.removePrefix(marker).trim()
  return checkout.resolve(gitDir).canonicalFile.toPath().toUri().toString()
}

val prepareSwiftSdkSearchPath = tasks.register("prepareSwiftSdkSearchPath") {
  description = "Creates a generated Swift SDK search path containing only the configured Android SDK."
  group = "build"

  // Capture script-level state into task-local vals so the doLast action below
  // never references the enclosing script object. A precompiled-script-plugin
  // top-level `val` is reached through the script instance, so a stored lambda
  // that names one captures `this$0`, which the configuration cache cannot
  // serialize. Locals are captured by value and stay clean.
  val artifactName = swiftSdkArtifactName
  val sdksDir = generatedSwiftSdksDir
  val bundleDir = swiftSdkBundleDir.map { File(it) }

  // Same skip-don't-fail contract as buildSwiftAndroid: an absent Swift Android
  // SDK must leave the APK without the host library, not break the build. onlyIf
  // alone is not enough — Gradle validates declared inputs before consulting it,
  // so a missing bundle would fail with an opaque "Property '$1' … doesn't exist"
  // before this task ever gets the chance to skip.
  val toolingAvailable = swiftAndroidToolingAvailable.get()
  onlyIf { toolingAvailable }

  inputs.dir(bundleDir).optional(true)
  outputs.dir(sdksDir)

  doLast {
    val searchDir = sdksDir.get().asFile
    val bundleLink = searchDir.resolve("$artifactName.artifactbundle")
    // `Files.deleteIfExists` (not `project.delete`) so this action does not touch
    // `Task.project` at execution time — required for configuration-cache
    // compatibility. It removes the symlink itself without following it into the
    // real SDK bundle, matching the prior `project.delete` semantics.
    Files.deleteIfExists(bundleLink.toPath())
    searchDir.mkdirs()
    Files.createSymbolicLink(
      bundleLink.toPath(),
      bundleDir.get().toPath()
    )
  }
}

val configureSwiftPackageMirrors = tasks.register<Exec>("configureSwiftPackageMirrors") {
  description = "Mirrors the public SwiftTUI dependency to a local checkout when available."
  group = "build"

  val packageDir = hostConfig.packageDirectory.get().asFile
  // Unset is the ordinary case: no mirror, no message. Only report when a
  // checkout was explicitly requested but is not there, which is a real mistake.
  val checkout = hostConfig.swiftTuiCheckout.orNull?.asFile

  onlyIf {
    when {
      checkout == null -> false
      !checkout.isDirectory -> {
        logger.lifecycle(
          "SwiftTUI: requested local checkout $checkout is not a directory; " +
            "using the tagged dependency instead."
        )
        false
      }
      else -> true
    }
  }

  // Exec demands a command line at configuration time even when onlyIf will skip
  // the task, so stand in a no-op when no checkout was requested.
  if (checkout == null) {
    commandLine("true")
  } else {
    commandLine(
      "swiftly",
      "run",
      "swift",
      "package",
      "--package-path",
      packageDir.absolutePath,
      "config",
      "set-mirror",
      "--original",
      swiftTuiDependencyUrl,
      "--mirror",
      swiftPackageMirrorUrl(checkout)
    )
  }
}

val buildSwiftAndroid = tasks.register<Exec>("buildSwiftAndroid") {
  description = "Builds the configured Swift host product as an Android arm64 dynamic library."
  group = "build"

  val packageDir = hostConfig.packageDirectory.get().asFile
  val product = hostConfig.productName.get()

  // Skip (rather than fail) when the Android NDK or Swift Android SDK bundle is
  // absent, so a JVM-only `testDebugUnitTest` gate can run without them. Resolve
  // at configuration time and capture as a task-local so the onlyIf spec does not
  // reference the script object (config-cache safe). NDK/SDK presence is stable
  // across a build, so a config-time decision is equivalent to a lazy one.
  val toolingAvailable = swiftAndroidToolingAvailable.get()
  onlyIf {
    if (!toolingAvailable) {
      logger.lifecycle(
        "Skipping Swift Android build: NDK or Swift Android SDK bundle not found. " +
          "The APK will lack the Swift host library; unit tests are unaffected."
      )
    }
    toolingAvailable
  }
  dependsOn(configureSwiftPackageMirrors, prepareSwiftSdkSearchPath)

  inputs.files(fileTree(packageDir) {
    include("Package.swift")
    include("Sources/**/*.swift")
  })
  inputs.files(hostConfig.additionalSwiftSources)
  // Only when a local checkout is mirrored in: without one the framework arrives
  // as a resolved tagged dependency, whose sources are not an input to track.
  hostConfig.swiftTuiCheckout.orNull?.let { localCheckout ->
    inputs.files(fileTree(localCheckout) {
      include("Package.swift")
      include("Sources/**/*.swift")
      include("Platforms/Android/**/*.swift")
      include("Vendor/swift-figlet/**/*.swift")
    })
  }
  outputs.file(File(packageDir, "$swiftBuildSubpath/lib$product.so"))

  environment("DISABLE_EXPLICIT_PLATFORMS", "1")
  environment("ANDROID_NDK_HOME", swiftAndroidNdkDir.get())
  commandLine(
    "swiftly",
    "run",
    "swift",
    "build",
    swiftToolchainVersion,
    "--package-path",
    packageDir.absolutePath,
    "--swift-sdks-path",
    generatedSwiftSdksDir.get().asFile.absolutePath,
    "--swift-sdk",
    swiftSdkName,
    "--product",
    product
  )
}

// Sync (not Copy) so the generated jniLibs dir exactly mirrors the produced set:
// a renamed product (e.g. libCounterAndroidHost.so -> libswift_tui_app_host.so)
// must not leave the old `.so` orphaned in the merged APK.
val copySwiftAndroidLibraries = tasks.register<Sync>("copySwiftAndroidLibraries") {
  description = "Syncs the Swift host product (renamed to canonical) and Swift runtime into jniLibs."
  group = "build"
  // Config-time capture (see buildSwiftAndroid): keeps the onlyIf spec free of any
  // script-object reference so it is configuration-cache serializable.
  val toolingAvailable = swiftAndroidToolingAvailable.get()
  onlyIf { toolingAvailable }
  dependsOn(buildSwiftAndroid)

  val packageDir = hostConfig.packageDirectory.get().asFile
  val product = hostConfig.productName.get()
  val canonical = hostConfig.hostLibraryName.get()

  from(File(packageDir, swiftBuildSubpath)) {
    include("lib$product.so")
    // Standardize the per-app Swift product to the canonical name the host
    // library's JNI shim dlopen()s (D2). Decouples the on-device library name
    // from any one consumer's SwiftPM product name.
    rename("lib$product.so", "lib$canonical.so")
  }
  from(swiftRuntimeLibDir) {
    include("*.so")
  }
  from(ndkCxxSharedLib) {
    include("libc++_shared.so")
  }
  into(generatedJniLibsDir.map { it.dir("arm64-v8a") })
}

// This plugin stages the host `.so` and the Swift runtime, but *packaging* them
// stays the app's decision: the plugin takes no AGP dependency, so it cannot add
// the generated directory to the app's `android {}` jniLibs source set itself.
//
// When an app omits that one line the failure is silent and expensive: the APK
// builds clean, installs, and launches, but `libswift_tui_app_host.so` is absent,
// so the JNI shim's dlopen() fails and the host shows an empty view. The only
// trace is a logcat line. Detect the omission at build time instead.
//
// Resolved during configuration into a plain Boolean so the task input carries no
// reference to the AGP extension (configuration-cache safe). Defaults to `true`:
// introspection failure must never fail a build that would otherwise work.
val jniLibsWired = objects.property(Boolean::class.java).convention(true)

afterEvaluate {
  // Read `android.sourceSets.main.jniLibs.directories` reflectively — matching
  // how this plugin already addresses AGP by name (see the preBuild hook below)
  // rather than taking a compile-time dependency on it. Any failure here leaves
  // the check disabled rather than guessing.
  val declared: Set<String> = runCatching {
    val android = extensions.findByName("android") ?: return@runCatching emptySet()
    fun call(target: Any, method: String): Any? =
      target.javaClass.methods
        .first { it.name == method && it.parameterCount == 0 }
        .apply { isAccessible = true }
        .invoke(target)

    val sourceSets = call(android, "getSourceSets") as NamedDomainObjectContainer<*>
    val main = sourceSets.getByName("main") as Any
    val jniLibs = call(main, "getJniLibs") as Any
    (call(jniLibs, "getDirectories") as Collection<*>).mapNotNull { it?.toString() }.toSet()
  }.getOrElse { return@afterEvaluate }

  if (declared.isEmpty()) return@afterEvaluate

  // Compare resolved paths, not strings: `directories.add(...)` takes an absolute
  // path while the deprecated `srcDir(...)` accepts a provider or a relative one.
  val expected = generatedJniLibsDir.get().asFile.canonicalFile
  jniLibsWired.set(
    declared.any { runCatching { file(it).canonicalFile == expected }.getOrDefault(false) }
  )
}

val verifySwiftAndroidJniLibs = tasks.register("verifySwiftAndroidJniLibs") {
  description = "Fails if the app does not package the staged Swift host libraries."
  group = "verification"

  // Only meaningful when a Swift build actually produced libraries to package.
  // Without the toolchain nothing is staged, so the app packaging nothing is
  // correct, not a misconfiguration (mirrors buildSwiftAndroid's onlyIf).
  val toolingAvailable = swiftAndroidToolingAvailable.get()
  onlyIf { toolingAvailable }

  // Task-local captures: the doLast body must not reference `hostConfig` or the
  // enclosing script object (configuration-cache safe, as elsewhere in this file).
  val wired = jniLibsWired
  val stagedPath = generatedJniLibsDir.map { it.asFile.path }
  val canonicalLibrary = hostConfig.hostLibraryName.get()

  doLast {
    if (wired.get()) return@doLast
    throw GradleException(
      """
      SwiftTUI: this app builds the Swift host library but never packages it.

        staged at: ${stagedPath.get()}

      The APK would build, install, and launch with no Swift host inside it:
      dlopen(lib$canonicalLibrary.so) fails and the view stays blank, reporting
      only to logcat.

      Add both blocks to this module's android { } in build.gradle.kts:

        sourceSets["main"].jniLibs.directories.add(
          layout.buildDirectory.dir("generated/swiftJniLibs").get().asFile.path
        )

        packaging {
          jniLibs {
            // Extract the Swift runtime at install time so dlopen resolves it.
            useLegacyPackaging = true
          }
        }
      """.trimIndent()
    )
  }
}

// preBuild is created by AGP; wire lazily so apply-order does not matter.
tasks.matching { it.name == "preBuild" }.configureEach {
  dependsOn(copySwiftAndroidLibraries, verifySwiftAndroidJniLibs)
}
