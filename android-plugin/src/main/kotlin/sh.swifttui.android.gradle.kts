import java.io.File
import java.nio.file.Files

// SwiftTUI Android convention plugin. Owns the per-app, per-ABI Swift -> `.so`
// cross-build, the Swift-SDK search path, the dev dependency mirror, and the
// jniLibs copy (renaming the product to the canonical host library name). The
// app applies `id("sh.swifttui.android")` instead of pasting these tasks.

val hostConfig = extensions.create("swiftTuiAndroidHost", SwiftTuiAndroidHostExtension::class.java)
hostConfig.hostLibraryName.convention("swift_tui_app_host")
hostConfig.packageDirectory.convention(layout.projectDirectory.dir("../SwiftPackage"))
// arm64-v8a alone unless the consumer says otherwise: a second ABI is a second
// full Swift cross-build and a second Swift runtime in the APK, so it is opt-in.
hostConfig.abis.convention(setOf(SwiftTuiAndroidAbi.DEFAULT.abiName))

// The consumer's ABI selection, validated and in a fixed order. Lazy because
// `abis` is assigned in the consumer's build script, after this plugin is
// applied. Resolving it throws for an empty selection or an ABI the plugin has
// no complete mapping for (see SwiftTuiAndroidAbi), which fails the build while
// the task graph is being built — before any Swift is compiled, and whether or
// not the Swift toolchain is installed: a misspelt ABI is a configuration
// error, not missing tooling.
//
// `private`, like every declaration below that names SwiftTuiAndroidAbi: a
// script's top-level declarations are public members of the plugin class, and
// the ABI table is internal to the plugin, not API for build scripts.
private val selectedAbis: Provider<List<SwiftTuiAndroidAbi>> =
  hostConfig.abis.map { SwiftTuiAndroidAbi.resolve(it) }

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

val swiftToolchainVersion = "+6.4.3"
val swiftSdkArtifactName = "swift-6.4.3-RELEASE_android"
val swiftTuiDependencyUrl = "https://github.com/SwiftTUI/swift-tui.git"
// The jniLibs root the app adds to its source set. Each selected ABI is staged
// into its own `<abi>/` subdirectory, the layout AGP expects.
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
val defaultAndroidSdkDir = if (
  providers.systemProperty("os.name").get().lowercase().contains("mac")
) "$userHome/Library/Android/sdk" else "$userHome/Android/Sdk"
val defaultAndroidNdkDir = providers.environmentVariable("ANDROID_HOME")
  .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
  .orElse(defaultAndroidSdkDir)
  .map { "$it/ndk/30.0.16248370" }
val swiftAndroidRoot = providers.environmentVariable("SWIFT_ANDROID_ROOT")
  .orElse(defaultSwiftAndroidRoot)
val swiftAndroidNdkDir = providers.environmentVariable("ANDROID_NDK_HOME")
  .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
  .orElse(defaultAndroidNdkDir)
// The Swift runtime `.so` directory for one ABI inside the Swift Android SDK.
private fun swiftRuntimeLibDir(abi: SwiftTuiAndroidAbi): Provider<File> = swiftAndroidRoot.map {
  // java.io.File (not Gradle's `file()`), so this provider does not capture the
  // enclosing script object — required for configuration-cache compatibility.
  File("$it/${abi.swiftRuntimeSubpath}")
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
// The NDK's `libc++_shared.so` for one ABI. It must come from the same NDK the
// Swift SDK sysroot was materialized from: the Swift 6.4 runtime needs NDK 30's.
private fun ndkCxxSharedLib(abi: SwiftTuiAndroidAbi): Provider<File> =
  swiftAndroidNdkDir.zip(ndkHostTag) { ndkDir, hostTag ->
    File("$ndkDir/${abi.ndkCxxSharedSubpath(hostTag)}")
  }

// True only when both the Android NDK and the Swift Android SDK bundle are
// present. When false the native Swift build is skipped so a JVM-only
// `testDebugUnitTest` gate can configure and run without them.
val swiftAndroidToolingAvailable = providers.provider {
  File(swiftAndroidNdkDir.get()).exists() && File(swiftSdkBundleDir.get()).exists()
}

// swiftly installs to `~/.swiftly/bin` and depends on a shell profile to put that
// directory on PATH. An IDE launched from a desktop shell never reads that
// profile: on macOS a Finder- or Dock-launched Android Studio inherits launchd's
// PATH (`/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin`), and so does the Gradle
// daemon it forks. A bare `swiftly` command line therefore dies with
// `Cannot run program "swiftly" ... error=2` on a machine where swiftly is
// installed and the identical build succeeds from a terminal — an error that
// accuses the one component the user can plainly see is present.
//
// Resolve an absolute path up front instead, and when none is found, say why.
// `providers.environmentVariable` rather than `System.getenv` so PATH and
// SWIFTLY_BIN_DIR are declared configuration-cache inputs: moving swiftly must
// re-resolve rather than replay a stale absolute path out of the cache.
val swiftlySearchDirs: List<String> = buildList {
  // An explicit install dir wins over the default, which wins over PATH: a
  // consumer who deliberately moved swiftly should not be overruled by whatever
  // a stale PATH entry happens to name first.
  providers.environmentVariable("SWIFTLY_BIN_DIR").orNull?.let(::add)
  add("$userHome/.swiftly/bin")
  providers.environmentVariable("PATH").orNull
    ?.split(File.pathSeparator)
    ?.let(::addAll)
}.filter { it.isNotBlank() }

val discoveredSwiftly: File? = swiftlySearchDirs
  .asSequence()
  .map { File(it, "swiftly") }
  .firstOrNull { it.isFile && it.canExecute() }

fun swiftlyUnavailableMessage(reason: String): String =
  """
  SwiftTUI: $reason

  swiftly installs to ~/.swiftly/bin, which a shell profile puts on PATH. An IDE
  launched from the desktop (Finder, the Dock, a launcher) does not read that
  profile and hands its own reduced PATH to Gradle. That is why this can fail
  while the same build succeeds from a terminal.

  Any one of these fixes it:
    - Relaunch the IDE from a terminal so it inherits your shell's PATH.
    - Name swiftly in this module's build.gradle.kts:
        swiftTuiAndroidHost { swiftlyExecutable = file("<path>/swiftly") }
    - Export SWIFTLY_BIN_DIR in the environment Gradle runs in.
  """.trimIndent()

// Sets the receiver's command line to `<swiftly> run swift <args>`, using an
// absolute launcher path so PATH is never consulted at execution time.
fun Exec.swiftlyRun(vararg args: String) {
  // An explicit `swiftlyExecutable` is never silently discarded in favour of a
  // discovered one: a consumer who named a path and got a build against some
  // other toolchain would have no way to tell. Report the bad path instead.
  val explicit = hostConfig.swiftlyExecutable.orNull?.asFile
  val launcher = (explicit ?: discoveredSwiftly)?.takeIf { it.isFile && it.canExecute() }

  if (launcher != null) {
    commandLine(listOf(launcher.absolutePath, "run", "swift") + args)
    return
  }

  val reason = when (explicit) {
    null ->
      "could not find the `swiftly` Swift toolchain launcher.\n\n" +
        "    searched: ${swiftlySearchDirs.joinToString(", ")}"
    else -> "swiftlyExecutable names $explicit, which is not an executable file."
  }

  // Exec demands a command line at configuration time even for a task that will
  // not run one, and a doFirst that throws pre-empts the exec. Reporting here
  // rather than letting the process launch fail replaces an opaque
  // `Cannot run program "swiftly"` with something that names the cause. The
  // message is captured as a task-local String so the stored action holds no
  // reference to the enclosing script object (configuration-cache safe, as
  // elsewhere in this file).
  val message = swiftlyUnavailableMessage(reason)
  commandLine("true")
  doFirst { throw GradleException(message) }
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
    swiftlyRun(
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

// One Swift cross-build per supported ABI. Every ABI has its own task, its own
// `--swift-sdk` triple and its own SwiftPM scratch and output directories (see
// SwiftTuiAndroidAbi.swiftScratchSubpath for why they cannot be shared), and
// the triple is a declared input, so Gradle tracks each ABI's product
// separately: an up-to-date arm64 build can never stand in for an x86_64 one.
// Tasks exist for every supported ABI; only the selected ones are wired into
// the build (see `buildSwiftAndroid` below).
private val swiftAbiBuildTasks: Map<SwiftTuiAndroidAbi, TaskProvider<Exec>> =
  SwiftTuiAndroidAbi.entries.associateWith { abi ->
    tasks.register<Exec>("buildSwiftAndroid${abi.taskSuffix}") {
      description = "Builds the configured Swift host product as an Android ${abi.abiName} dynamic library."
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
      inputs.property("swiftSdkTriple", abi.swiftSdkTriple)
      outputs.file(File(packageDir, "${abi.swiftBuildSubpath}/lib$product.so"))

      environment("DISABLE_EXPLICIT_PLATFORMS", "1")
      environment("ANDROID_NDK_HOME", swiftAndroidNdkDir.get())
      // Only for a non-default ABI: the default one keeps SwiftPM's `.build`,
      // and so the exact command line this plugin has always run.
      val scratchPathArguments = abi.swiftScratchSubpath
        ?.let { listOf("--scratch-path", File(packageDir, it).absolutePath) }
        .orEmpty()
      swiftlyRun(
        "build",
        swiftToolchainVersion,
        "--build-system",
        "native",
        "--package-path",
        packageDir.absolutePath,
        *scratchPathArguments.toTypedArray(),
        "--swift-sdks-path",
        generatedSwiftSdksDir.get().asFile.absolutePath,
        "--swift-sdk",
        abi.swiftSdkTriple,
        "--product",
        product
      )

      // Reject an ABI the installed toolchain cannot complete, before spending a
      // Swift cross-build on it. A Swift SDK bundle or NDK without this ABI would
      // otherwise fail deep inside the compiler, or — for the runtime, which the
      // stage step copies with a pattern — succeed and stage nothing, leaving an
      // APK whose host library cannot load. Task-local captures only (see above).
      val abiName = abi.abiName
      val triple = abi.swiftSdkTriple
      val swiftCore = swiftRuntimeLibDir(abi).map { File(it, "libswiftCore.so") }
      val cxxShared = ndkCxxSharedLib(abi)
      doFirst {
        val missing = listOf(
          swiftCore.get() to "Swift runtime, from the Swift Android SDK bundle",
          cxxShared.get() to "C++ runtime, from the Android NDK"
        ).filterNot { (file, _) -> file.isFile }
        if (missing.isNotEmpty()) {
          throw GradleException(
            "SwiftTUI: cannot build the Swift host for Android ABI $abiName: " +
              "the installed toolchain is incomplete for it.\n\n" +
              missing.joinToString("\n") { (file, role) -> "  missing: $file\n           ($role)" } +
              "\n\nInstall a Swift Android SDK bundle and NDK that ship $triple, " +
              "or remove \"$abiName\" from swiftTuiAndroidHost.abis."
          )
        }
      }
    }
  }

// The ABI builds have separate scratch directories but one package: they share
// `Package.resolved`, the package's `.swiftpm` configuration and the machine's
// cores. Order them rather than let Gradle start two full Swift builds at once
// (it may run same-project tasks in parallel under the configuration cache).
SwiftTuiAndroidAbi.entries.zipWithNext { earlier, later ->
  val earlierBuild = swiftAbiBuildTasks.getValue(earlier)
  swiftAbiBuildTasks.getValue(later).configure { mustRunAfter(earlierBuild) }
}

val buildSwiftAndroid = tasks.register("buildSwiftAndroid") {
  description = "Builds the configured Swift host product for every selected Android ABI."
  group = "build"
  // Task-local, so the dependency provider does not reach through the script.
  val abiBuilds = swiftAbiBuildTasks
  dependsOn(selectedAbis.map { abis -> abis.map { abiBuilds.getValue(it) } })
}

// One stage step per supported ABI, each owning `jniLibs/<abi>` and nothing
// else. Sync (not Copy) so that directory exactly mirrors the produced set: a
// renamed product (e.g. libCounterAndroidHost.so -> libswift_tui_app_host.so)
// must not leave the old `.so` orphaned in the merged APK. Every source below is
// this ABI's own — its SwiftPM output directory, its Swift runtime, its NDK
// libc++ — so no ABI's directory can receive another's libraries.
private val swiftAbiStageTasks: Map<SwiftTuiAndroidAbi, TaskProvider<Sync>> =
  SwiftTuiAndroidAbi.entries.associateWith { abi ->
    tasks.register<Sync>("stageSwiftAndroidLibraries${abi.taskSuffix}") {
      description =
        "Syncs the ${abi.abiName} Swift host product (renamed to canonical) and Swift runtime into jniLibs."
      group = "build"
      // Config-time capture (see the build tasks): keeps the onlyIf spec free of any
      // script-object reference so it is configuration-cache serializable.
      val toolingAvailable = swiftAndroidToolingAvailable.get()
      onlyIf { toolingAvailable }
      dependsOn(swiftAbiBuildTasks.getValue(abi))

      val packageDir = hostConfig.packageDirectory.get().asFile
      val product = hostConfig.productName.get()
      val canonical = hostConfig.hostLibraryName.get()

      inputs.property("abi", abi.abiName)
      from(File(packageDir, abi.swiftBuildSubpath)) {
        include("lib$product.so")
        // Standardize the per-app Swift product to the canonical name the host
        // library's JNI shim dlopen()s (D2). Decouples the on-device library name
        // from any one consumer's SwiftPM product name.
        rename("lib$product.so", "lib$canonical.so")
      }
      from(swiftRuntimeLibDir(abi)) {
        include("*.so")
      }
      from(ndkCxxSharedLib(abi)) {
        include("libc++_shared.so")
      }
      into(generatedJniLibsDir.map { it.dir(abi.abiName) })

      // A copy pattern that matches nothing is not an error to Sync, so check the
      // result: every ABI must end up with the host library, the Swift runtime
      // and the C++ runtime, or the app cannot start on that ABI.
      val abiName = abi.abiName
      val stagedDir = generatedJniLibsDir.map { it.dir(abi.abiName).asFile }
      val required = listOf("lib$canonical.so", "libswiftCore.so", "libc++_shared.so")
      doLast {
        val directory = stagedDir.get()
        val missing = required.filterNot { File(directory, it).isFile }
        if (missing.isNotEmpty()) {
          throw GradleException(
            "SwiftTUI: the staged $abiName libraries are incomplete.\n\n" +
              "  staged at: $directory\n" +
              "  missing:   ${missing.joinToString(", ")}\n\n" +
              "The app could not load its Swift host on $abiName devices."
          )
        }
      }
    }
  }

// The step the app's build hangs off: stages every selected ABI, then removes
// any other ABI directory left in the jniLibs root by an earlier selection.
// Without that, deselecting x86_64 would keep packaging the stale x86_64
// libraries from the last build that had it. It declares no outputs of its own
// (each `jniLibs/<abi>` belongs to that ABI's stage task), so it always runs;
// the work is one directory listing.
val copySwiftAndroidLibraries = tasks.register("copySwiftAndroidLibraries") {
  description = "Stages the Swift host product and Swift runtime for every selected ABI into jniLibs."
  group = "build"
  val toolingAvailable = swiftAndroidToolingAvailable.get()
  onlyIf { toolingAvailable }

  val abiStages = swiftAbiStageTasks
  dependsOn(selectedAbis.map { abis -> abis.map { abiStages.getValue(it) } })

  val stagedRoot = generatedJniLibsDir.map { it.asFile }
  val selectedNames = selectedAbis.map { abis -> abis.map { it.abiName } }
  doLast {
    val keep = selectedNames.get()
    stagedRoot.get().listFiles()
      ?.filter { it.name !in keep }
      ?.forEach { it.deleteRecursively() }
  }
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

// The second silent failure of the same kind: the app packages an ABI the plugin
// stages nothing for. The AAR ships its JNI shim for every supported ABI, so an
// APK whose `abiFilters` name x86_64 while `swiftTuiAndroidHost.abis` does not
// gets `lib/x86_64/libswift_tui_jni.so` and no Swift host beside it. It installs
// on an x86_64 device and shows a blank view.
//
// Read `android.defaultConfig.ndk.abiFilters` the same reflective way. Empty
// means "no filter declared there" (or an AGP shape this cannot read), and the
// check then stays out of the way, as above. Only `defaultConfig` is read: a
// flavor or build type that narrows the filter further is always safe, and one
// that widens it is not visible from here.
val packagedAbiFilters = objects.setProperty(String::class.java).convention(emptySet())

afterEvaluate {
  val filters: Set<String> = runCatching {
    val android = extensions.findByName("android") ?: return@runCatching emptySet()
    fun call(target: Any, method: String): Any? =
      target.javaClass.methods
        .first { it.name == method && it.parameterCount == 0 }
        .apply { isAccessible = true }
        .invoke(target)

    val defaultConfig = call(android, "getDefaultConfig") as Any
    val ndk = call(defaultConfig, "getNdk") as Any
    (call(ndk, "getAbiFilters") as Collection<*>).mapNotNull { it?.toString() }.toSet()
  }.getOrElse { return@afterEvaluate }

  packagedAbiFilters.set(filters)
}

val verifySwiftAndroidJniLibs = tasks.register("verifySwiftAndroidJniLibs") {
  description = "Fails if the app does not package the staged Swift host libraries for every ABI it ships."
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
  val packagedAbis = packagedAbiFilters
  val stagedAbis = selectedAbis.map { abis -> abis.map { it.abiName } }

  doLast {
    val unstaged = packagedAbis.get().filterNot { it in stagedAbis.get() }.sorted()
    if (unstaged.isNotEmpty()) {
      throw GradleException(
        """
        SwiftTUI: this app packages an Android ABI it stages no Swift host for: ${unstaged.joinToString(", ")}.

          android.defaultConfig.ndk.abiFilters: ${packagedAbis.get().sorted().joinToString(", ")}
          swiftTuiAndroidHost.abis:             ${stagedAbis.get().joinToString(", ")}

        The APK would install on ${unstaged.joinToString(", ")} devices with the JNI shim and no
        lib$canonicalLibrary.so: dlopen fails and the view stays blank, reporting
        only to logcat.

        Either stage the Swift host for every packaged ABI:

          swiftTuiAndroidHost { abis = setOf(${packagedAbis.get().sorted().joinToString(", ") { "\"$it\"" }}) }

        or remove ${unstaged.joinToString(", ")} from abiFilters.
        """.trimIndent()
      )
    }

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
