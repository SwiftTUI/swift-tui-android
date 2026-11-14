import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs the real plugin in real Gradle builds against a fake Swift toolchain.
 *
 * The fake has the same shape as the real one — a `swiftly` launcher, a Swift
 * Android SDK bundle with one runtime directory per architecture, and an NDK
 * with one `libc++_shared.so` per ABI — but every "library" is a text file
 * naming the triple it stands for. That makes ABI mix-ups directly observable:
 * a staged `arm64-v8a/libswiftCore.so` that says `x86_64` is a failed test. It
 * also keeps the gate free of Swift and the NDK.
 */
class SwiftTuiAndroidPluginFunctionalTest {
  @get:Rule
  val temporary = TemporaryFolder()

  private lateinit var root: File
  private lateinit var project: File
  private lateinit var packageDir: File
  private lateinit var ndk: File
  private lateinit var bundle: File
  private lateinit var swiftly: File
  private lateinit var swiftlyLog: File

  private val ndkHostTag: String = System.getProperty("os.name").lowercase().let { os ->
    when {
      os.contains("mac") -> "darwin-x86_64"
      os.contains("linux") -> "linux-x86_64"
      else -> "windows-x86_64"
    }
  }

  @Before
  fun createFixture() {
    // Resolve symlinks (macOS temp lives behind /var -> /private/var) so paths
    // the build prints compare equal to paths the test computes.
    root = temporary.root.canonicalFile
    project = File(root, "app").apply { mkdirs() }
    // The plugin's default: `../SwiftPackage`, a sibling of the app module.
    packageDir = File(root, "SwiftPackage")
    ndk = File(root, "ndk")
    bundle = File(root, "swift-fake_android.artifactbundle")
    swiftly = File(root, "bin/swiftly")
    swiftlyLog = File(root, "swiftly.log")

    File(packageDir, "Sources/DemoHost").mkdirs()
    File(packageDir, "Package.swift").writeText("// swift-tools-version: 6.4\n")
    File(packageDir, "Sources/DemoHost/Demo.swift").writeText("// demo\n")

    for (abi in SwiftTuiAndroidAbi.entries) {
      installSwiftRuntime(abi)
      installNdkCxxShared(abi)
    }

    // Stands in for `swiftly run swift build … --swift-sdk <triple> --product <name>`:
    // records the invocation and writes a product that names its triple, into
    // `--scratch-path` when given and SwiftPM's default `.build` otherwise.
    swiftly.parentFile.mkdirs()
    swiftly.writeText(
      """
      #!/bin/sh
      set -eu
      printf '%s\n' "${'$'}*" >> "${'$'}FAKE_SWIFTLY_LOG"
      package=""
      scratch=""
      triple=""
      product=""
      while [ ${'$'}# -gt 0 ]; do
        case "${'$'}1" in
          --package-path) package=${'$'}2; shift 2 ;;
          --scratch-path) scratch=${'$'}2; shift 2 ;;
          --swift-sdk) triple=${'$'}2; shift 2 ;;
          --product) product=${'$'}2; shift 2 ;;
          *) shift ;;
        esac
      done
      [ -n "${'$'}triple" ] || exit 0
      [ -n "${'$'}scratch" ] || scratch="${'$'}package/.build"
      mkdir -p "${'$'}scratch/${'$'}triple/debug"
      printf 'host %s\n' "${'$'}triple" > "${'$'}scratch/${'$'}triple/debug/lib${'$'}product.so"
      """.trimIndent() + "\n"
    )
    swiftly.setExecutable(true)

    File(project, "settings.gradle").writeText("rootProject.name = 'fixture'\n")
    writeBuildScript()
  }

  private fun installSwiftRuntime(abi: SwiftTuiAndroidAbi) {
    val directory = File(bundle, "swift-android/${abi.swiftRuntimeSubpath}").apply { mkdirs() }
    for (library in listOf("libswiftCore.so", "libFoundation.so")) {
      File(directory, library).writeText("runtime ${abi.swiftArchitecture}\n")
    }
    // Not a shared library: the stage step must leave it behind.
    File(directory, "libswiftCxx.a").writeText("static ${abi.swiftArchitecture}\n")
  }

  private fun installNdkCxxShared(abi: SwiftTuiAndroidAbi) {
    File(ndk, abi.ndkCxxSharedSubpath(ndkHostTag)).apply {
      parentFile.mkdirs()
      writeText("c++ ${abi.ndkTriple}\n")
    }
  }

  /** A Groovy build script: no Kotlin script compilation, so each build is quick. */
  private fun writeBuildScript(abis: String? = null, extra: String = "") {
    File(project, "build.gradle").writeText(
      """
      plugins { id 'sh.swifttui.android' }

      swiftTuiAndroidHost {
        productName = 'DemoHost'
        swiftlyExecutable = file('${swiftly.path}')
        ${abis?.let { "abis = $it" } ?: ""}
      }

      // AGP creates preBuild in a real app; the plugin hangs its work off it.
      tasks.register('preBuild')
      $extra
      """.trimIndent() + "\n"
    )
  }

  private fun runner(vararg arguments: String, toolingPresent: Boolean = true): GradleRunner {
    val environment = System.getenv().toMutableMap()
    // Anything that would point the plugin at this machine's real toolchain.
    for (name in listOf(
      "ANDROID_HOME", "ANDROID_SDK_ROOT", "ANDROID_NDK_ROOT", "SWIFT_ANDROID_ROOT",
      "SWIFTTUI_LOCAL_CHECKOUT", "SWIFTLY_BIN_DIR"
    )) {
      environment.remove(name)
    }
    environment["ANDROID_NDK_HOME"] = if (toolingPresent) ndk.path else File(root, "no-ndk").path
    environment["SWIFT_ANDROID_SDK_BUNDLE"] = bundle.path
    environment["FAKE_SWIFTLY_LOG"] = swiftlyLog.path

    return GradleRunner.create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withTestKitDir(File(requireNotNull(System.getProperty("swifttui.testKitDir"))))
      .withEnvironment(environment)
      .withArguments(*arguments, "--stacktrace")
  }

  private fun BuildResult.outcome(task: String): TaskOutcome? = task(":$task")?.outcome

  private fun staged(path: String): File = File(project, "build/generated/swiftJniLibs/$path")

  private fun stagedAbiDirectories(): List<String> =
    staged("").listFiles().orEmpty().map { it.name }.sorted()

  private fun stagedLibraries(abi: String): List<String> =
    staged(abi).listFiles().orEmpty().map { it.name }.sorted()

  private fun swiftBuilds(): List<String> =
    if (swiftlyLog.exists()) swiftlyLog.readLines().filter { it.contains(" build ") } else emptyList()

  private fun assertStagedFrom(abi: SwiftTuiAndroidAbi) {
    assertEquals(
      "every ${abi.abiName} library and nothing else",
      listOf("libFoundation.so", "libc++_shared.so", "libswiftCore.so", "libswift_tui_app_host.so"),
      stagedLibraries(abi.abiName)
    )
    assertEquals("host ${abi.swiftSdkTriple}\n", staged("${abi.abiName}/libswift_tui_app_host.so").readText())
    assertEquals("runtime ${abi.swiftArchitecture}\n", staged("${abi.abiName}/libswiftCore.so").readText())
    assertEquals("runtime ${abi.swiftArchitecture}\n", staged("${abi.abiName}/libFoundation.so").readText())
    assertEquals("c++ ${abi.ndkTriple}\n", staged("${abi.abiName}/libc++_shared.so").readText())
  }

  @Test
  fun selectingNothingBuildsAndStagesArm64Alone() {
    val result = runner("preBuild").build()

    assertEquals(TaskOutcome.SUCCESS, result.outcome("buildSwiftAndroidArm64V8a"))
    assertEquals(TaskOutcome.SUCCESS, result.outcome("stageSwiftAndroidLibrariesArm64V8a"))
    assertEquals(TaskOutcome.SUCCESS, result.outcome("copySwiftAndroidLibraries"))
    assertNull("x86_64 must not be built by default", result.outcome("buildSwiftAndroidX86_64"))
    assertNull("x86_64 must not be staged by default", result.outcome("stageSwiftAndroidLibrariesX86_64"))

    assertEquals(listOf("arm64-v8a"), stagedAbiDirectories())
    assertStagedFrom(SwiftTuiAndroidAbi.ARM64_V8A)
    assertEquals(1, swiftBuilds().size)
    assertTrue(swiftBuilds().single().contains("--swift-sdk aarch64-unknown-linux-android28 --product DemoHost"))
    // The command an arm64-only app has always run: SwiftPM's default scratch
    // directory, so no flag naming another one.
    assertFalse(swiftBuilds().single(), swiftBuilds().single().contains("--scratch-path"))
    assertTrue(File(packageDir, ".build/aarch64-unknown-linux-android28/debug/libDemoHost.so").isFile)
  }

  @Test
  fun eachSelectedAbiIsBuiltForItsOwnTripleAndStagedFromItsOwnProducts() {
    writeBuildScript(abis = "['x86_64', 'arm64-v8a']")

    val result = runner("preBuild").build()

    for (abi in SwiftTuiAndroidAbi.entries) {
      assertEquals(TaskOutcome.SUCCESS, result.outcome("buildSwiftAndroid${abi.taskSuffix}"))
      assertEquals(TaskOutcome.SUCCESS, result.outcome("stageSwiftAndroidLibraries${abi.taskSuffix}"))
      assertStagedFrom(abi)
    }
    assertEquals(listOf("arm64-v8a", "x86_64"), stagedAbiDirectories())
    assertEquals(
      listOf("aarch64-unknown-linux-android28", "x86_64-unknown-linux-android28"),
      swiftBuilds().map { it.substringAfter("--swift-sdk ").substringBefore(" ") }
    )
    // Each triple in its own SwiftPM scratch directory: arm64 in the default
    // `.build`, x86_64 in one of its own. Sharing one breaks real SwiftPM
    // builds (see SwiftTuiAndroidAbi.swiftScratchSubpath).
    val (arm64Build, x86Build) = swiftBuilds()
    assertFalse(arm64Build, arm64Build.contains("--scratch-path"))
    assertTrue(x86Build, x86Build.contains("--scratch-path ${File(packageDir, ".build/android-x86_64").path} "))
    assertTrue(File(packageDir, ".build/aarch64-unknown-linux-android28/debug/libDemoHost.so").isFile)
    assertTrue(File(packageDir, ".build/android-x86_64/x86_64-unknown-linux-android28/debug/libDemoHost.so").isFile)
    assertFalse(File(packageDir, ".build/x86_64-unknown-linux-android28").exists())
  }

  // The consumer-facing spelling, through the generated Kotlin accessors.
  @Test
  fun theKotlinDslAssignsTheAbiSet() {
    File(project, "build.gradle").delete()
    File(project, "build.gradle.kts").writeText(
      """
      plugins { id("sh.swifttui.android") }

      swiftTuiAndroidHost {
        productName = "DemoHost"
        swiftlyExecutable = file("${swiftly.path}")
        abis = setOf("arm64-v8a", "x86_64")
      }

      tasks.register("preBuild")
      """.trimIndent() + "\n"
    )

    runner("preBuild").build()

    assertEquals(listOf("arm64-v8a", "x86_64"), stagedAbiDirectories())
    SwiftTuiAndroidAbi.entries.forEach(::assertStagedFrom)
  }

  @Test
  fun anUnchangedBuildIsUpToDateAndDeselectingAnAbiRemovesItsStagedLibraries() {
    writeBuildScript(abis = "['arm64-v8a', 'x86_64']")
    runner("preBuild").build()

    val unchanged = runner("preBuild").build()
    for (abi in SwiftTuiAndroidAbi.entries) {
      assertEquals(TaskOutcome.UP_TO_DATE, unchanged.outcome("buildSwiftAndroid${abi.taskSuffix}"))
      assertEquals(TaskOutcome.UP_TO_DATE, unchanged.outcome("stageSwiftAndroidLibraries${abi.taskSuffix}"))
    }
    assertEquals(2, swiftBuilds().size)

    writeBuildScript(abis = "['arm64-v8a']")
    val narrowed = runner("preBuild").build()

    assertEquals(TaskOutcome.UP_TO_DATE, narrowed.outcome("buildSwiftAndroidArm64V8a"))
    assertNull(narrowed.outcome("buildSwiftAndroidX86_64"))
    assertEquals("the stale x86_64 directory must not reach the APK", listOf("arm64-v8a"), stagedAbiDirectories())
    assertStagedFrom(SwiftTuiAndroidAbi.ARM64_V8A)
    assertEquals(2, swiftBuilds().size)
  }

  @Test
  fun losingOneAbisProductRebuildsThatAbiAlone() {
    writeBuildScript(abis = "['arm64-v8a', 'x86_64']")
    runner("preBuild").build()

    File(packageDir, "${SwiftTuiAndroidAbi.X86_64.swiftBuildSubpath}/libDemoHost.so").delete()
    val result = runner("preBuild").build()

    assertEquals(TaskOutcome.UP_TO_DATE, result.outcome("buildSwiftAndroidArm64V8a"))
    assertEquals(TaskOutcome.UP_TO_DATE, result.outcome("stageSwiftAndroidLibrariesArm64V8a"))
    assertEquals(TaskOutcome.SUCCESS, result.outcome("buildSwiftAndroidX86_64"))
    assertEquals(
      listOf("aarch64-unknown-linux-android28", "x86_64-unknown-linux-android28", "x86_64-unknown-linux-android28"),
      swiftBuilds().map { it.substringAfter("--swift-sdk ").substringBefore(" ") }
    )
    SwiftTuiAndroidAbi.entries.forEach(::assertStagedFrom)
  }

  @Test
  fun anUnsupportedAbiFailsTheBuildWhetherOrNotTheToolchainIsInstalled() {
    writeBuildScript(abis = "['arm64-v8a', 'armeabi-v7a']")

    for (toolingPresent in listOf(true, false)) {
      val result = runner("preBuild", toolingPresent = toolingPresent).buildAndFail()

      assertTrue(result.output, result.output.contains("cannot package: armeabi-v7a"))
      assertTrue(result.output, result.output.contains("supported: arm64-v8a, x86_64"))
      assertTrue("nothing may be built for a rejected selection", swiftBuilds().isEmpty())
    }
  }

  @Test
  fun anEmptySelectionFailsTheBuild() {
    writeBuildScript(abis = "[]")

    val result = runner("preBuild").buildAndFail()

    assertTrue(result.output, result.output.contains("swiftTuiAndroidHost.abis is empty"))
    assertTrue(swiftBuilds().isEmpty())
  }

  @Test
  fun anAbiTheSwiftSdkLacksIsRejectedBeforeAnythingIsBuiltForIt() {
    File(bundle, "swift-android/${SwiftTuiAndroidAbi.X86_64.swiftRuntimeSubpath}").deleteRecursively()
    writeBuildScript(abis = "['arm64-v8a', 'x86_64']")

    val result = runner("preBuild", "--continue").buildAndFail()

    assertEquals(TaskOutcome.FAILED, result.outcome("buildSwiftAndroidX86_64"))
    assertTrue(result.output, result.output.contains("cannot build the Swift host for Android ABI x86_64"))
    assertTrue(result.output, result.output.contains("swift-x86_64/android/libswiftCore.so"))
    assertTrue(result.output, result.output.contains("remove \"x86_64\" from swiftTuiAndroidHost.abis"))
    assertTrue("no x86_64 compile may start", swiftBuilds().none { it.contains("x86_64-unknown-linux-android28") })
    assertFalse("no partial x86_64 directory may be staged", staged("x86_64").exists())
  }

  @Test
  fun anAbiTheNdkLacksIsRejectedBeforeAnythingIsBuiltForIt() {
    File(ndk, SwiftTuiAndroidAbi.X86_64.ndkCxxSharedSubpath(ndkHostTag)).delete()
    writeBuildScript(abis = "['x86_64']")

    val result = runner("preBuild").buildAndFail()

    assertEquals(TaskOutcome.FAILED, result.outcome("buildSwiftAndroidX86_64"))
    assertTrue(result.output, result.output.contains("x86_64-linux-android/libc++_shared.so"))
    assertTrue(swiftBuilds().isEmpty())
  }

  // The JVM-only gate: no NDK, no Swift SDK. Nothing is built, nothing fails.
  @Test
  fun absentToolingSkipsEverySelectedAbiWithoutFailing() {
    writeBuildScript(abis = "['arm64-v8a', 'x86_64']")

    val result = runner("preBuild", toolingPresent = false).build()

    for (task in listOf(
      "buildSwiftAndroidArm64V8a", "buildSwiftAndroidX86_64",
      "stageSwiftAndroidLibrariesArm64V8a", "stageSwiftAndroidLibrariesX86_64",
      "copySwiftAndroidLibraries", "verifySwiftAndroidJniLibs"
    )) {
      assertEquals(task, TaskOutcome.SKIPPED, result.outcome(task))
    }
    assertTrue(result.output, result.output.contains("Skipping Swift Android build"))
    assertTrue(swiftBuilds().isEmpty())
    assertFalse(staged("").exists())
  }

  // Stands in for AGP's `android { }` extension, which the plugin reads by name
  // and reflection rather than depending on AGP.
  private fun fakeAndroidExtension(abiFilters: String): String =
    """
    class FakeJniLibs { Set<String> directories = [] as Set }
    class FakeSourceSet implements Named {
      final String name
      FakeJniLibs jniLibs = new FakeJniLibs()
      FakeSourceSet(String name) { this.name = name }
    }
    class FakeNdk { Set<String> abiFilters = [] as Set }
    class FakeDefaultConfig { FakeNdk ndk = new FakeNdk() }
    class FakeAndroid {
      NamedDomainObjectContainer<FakeSourceSet> sourceSets
      FakeDefaultConfig defaultConfig = new FakeDefaultConfig()
    }

    def android = new FakeAndroid(sourceSets: project.container(FakeSourceSet))
    extensions.add('android', android)
    android.sourceSets.create('main').jniLibs.directories.add(
      layout.buildDirectory.dir('generated/swiftJniLibs').get().asFile.path
    )
    android.defaultConfig.ndk.abiFilters.addAll($abiFilters)
    """.trimIndent()

  @Test
  fun anApkAbiWithNoStagedSwiftHostFailsTheBuild() {
    writeBuildScript(extra = fakeAndroidExtension("['arm64-v8a', 'x86_64']"))

    val result = runner("verifySwiftAndroidJniLibs").buildAndFail()

    assertTrue(result.output, result.output.contains("packages an Android ABI it stages no Swift host for: x86_64"))
    assertTrue(result.output, result.output.contains("android.defaultConfig.ndk.abiFilters: arm64-v8a, x86_64"))
    assertTrue(result.output, result.output.contains("swiftTuiAndroidHost.abis:             arm64-v8a"))
    assertTrue(result.output, result.output.contains("""abis = setOf("arm64-v8a", "x86_64")"""))
  }

  @Test
  fun anApkWhoseAbisAreAllStagedPassesAndMayPackageFewerThanItStages() {
    writeBuildScript(
      abis = "['arm64-v8a', 'x86_64']",
      extra = fakeAndroidExtension("['arm64-v8a', 'x86_64']")
    )
    assertEquals(TaskOutcome.SUCCESS, runner("verifySwiftAndroidJniLibs").build().outcome("verifySwiftAndroidJniLibs"))

    writeBuildScript(
      abis = "['arm64-v8a', 'x86_64']",
      extra = fakeAndroidExtension("['arm64-v8a']")
    )
    assertEquals(TaskOutcome.SUCCESS, runner("verifySwiftAndroidJniLibs").build().outcome("verifySwiftAndroidJniLibs"))

    // No filter declared: the check has nothing to compare and stays out of the way.
    writeBuildScript(extra = fakeAndroidExtension("[]"))
    assertEquals(TaskOutcome.SUCCESS, runner("verifySwiftAndroidJniLibs").build().outcome("verifySwiftAndroidJniLibs"))
  }

  @Test
  fun theConfigurationCacheIsReusedForAMultiAbiBuild() {
    writeBuildScript(
      abis = "['arm64-v8a', 'x86_64']",
      extra = fakeAndroidExtension("['arm64-v8a', 'x86_64']")
    )

    val stored = runner("preBuild", "--configuration-cache").build()
    assertTrue(stored.output, stored.output.contains("Configuration cache entry stored"))

    val reused = runner("preBuild", "--configuration-cache").build()
    assertTrue(reused.output, reused.output.contains("Reusing configuration cache"))
    for (abi in SwiftTuiAndroidAbi.entries) {
      assertEquals(TaskOutcome.UP_TO_DATE, reused.outcome("buildSwiftAndroid${abi.taskSuffix}"))
      assertStagedFrom(abi)
    }
    assertEquals(2, swiftBuilds().size)
  }
}
