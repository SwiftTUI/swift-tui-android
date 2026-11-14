import java.io.File
import org.gradle.api.InvalidUserDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SwiftTuiAndroidAbiTest {
  @Test
  fun arm64MapsToTheAarch64ToolchainLane() {
    val abi = SwiftTuiAndroidAbi.ARM64_V8A
    assertEquals("arm64-v8a", abi.abiName)
    assertEquals("aarch64-unknown-linux-android28", abi.swiftSdkTriple)
    // SwiftPM's own default scratch directory: the command line and the output
    // location an arm64-only app has always had.
    assertEquals(null, abi.swiftScratchSubpath)
    assertEquals(".build/aarch64-unknown-linux-android28/debug", abi.swiftBuildSubpath)
    assertEquals("swift-resources/usr/lib/swift-aarch64/android", abi.swiftRuntimeSubpath)
    assertEquals(
      "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so",
      abi.ndkCxxSharedSubpath("linux-x86_64")
    )
    assertEquals("Arm64V8a", abi.taskSuffix)
  }

  @Test
  fun x86_64MapsToTheX86_64ToolchainLane() {
    val abi = SwiftTuiAndroidAbi.X86_64
    assertEquals("x86_64", abi.abiName)
    assertEquals("x86_64-unknown-linux-android28", abi.swiftSdkTriple)
    assertEquals(".build/android-x86_64", abi.swiftScratchSubpath)
    assertEquals(".build/android-x86_64/x86_64-unknown-linux-android28/debug", abi.swiftBuildSubpath)
    assertEquals("swift-resources/usr/lib/swift-x86_64/android", abi.swiftRuntimeSubpath)
    assertEquals(
      "toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/x86_64-linux-android/libc++_shared.so",
      abi.ndkCxxSharedSubpath("darwin-x86_64")
    )
    assertEquals("X86_64", abi.taskSuffix)
  }

  @Test
  fun noTwoAbisShareAnyToolchainPathOrTaskName() {
    val abis = SwiftTuiAndroidAbi.entries
    for (distinct in listOf<(SwiftTuiAndroidAbi) -> String>(
      { it.abiName },
      { it.swiftSdkTriple },
      // Two triples in one SwiftPM scratch directory break each other's builds.
      { it.swiftScratchSubpath ?: ".build" },
      { it.swiftBuildSubpath },
      { it.swiftRuntimeSubpath },
      { it.ndkCxxSharedSubpath("linux-x86_64") },
      { it.taskSuffix },
    )) {
      assertEquals(abis.size, abis.map(distinct).toSet().size)
    }
  }

  @Test
  fun theDefaultIsArm64Alone() {
    assertEquals(SwiftTuiAndroidAbi.ARM64_V8A, SwiftTuiAndroidAbi.DEFAULT)
  }

  @Test
  fun selectionResolvesInAFixedOrderWhateverOrderItWasWritten() {
    val expected = listOf(SwiftTuiAndroidAbi.ARM64_V8A, SwiftTuiAndroidAbi.X86_64)
    assertEquals(expected, SwiftTuiAndroidAbi.resolve(listOf("x86_64", "arm64-v8a")))
    assertEquals(expected, SwiftTuiAndroidAbi.resolve(linkedSetOf("arm64-v8a", "x86_64")))
    assertEquals(listOf(SwiftTuiAndroidAbi.X86_64), SwiftTuiAndroidAbi.resolve(setOf("x86_64")))
    assertEquals(listOf(SwiftTuiAndroidAbi.ARM64_V8A), SwiftTuiAndroidAbi.resolve(setOf("arm64-v8a")))
  }

  @Test
  fun anUnsupportedAbiIsRejectedByNameWithTheSupportedList() {
    for (unsupported in listOf("armeabi-v7a", "x86", "riscv64", "arm64", "aarch64", "ARM64-V8A", "")) {
      val failure = assertThrows(InvalidUserDataException::class.java) {
        SwiftTuiAndroidAbi.resolve(listOf("arm64-v8a", unsupported))
      }
      val message = failure.message.orEmpty()
      assertTrue(message, message.contains("cannot package: $unsupported"))
      assertTrue(message, message.contains("supported: arm64-v8a, x86_64"))
    }
  }

  @Test
  fun anEmptySelectionIsRejected() {
    val failure = assertThrows(InvalidUserDataException::class.java) {
      SwiftTuiAndroidAbi.resolve(emptySet())
    }
    assertTrue(failure.message.orEmpty().contains("swiftTuiAndroidHost.abis is empty"))
  }

  // The AAR builds its JNI shim for a fixed ABI list, declared twice (AGP's
  // abiFilters and ndk-build's APP_ABI). The plugin must stage Swift for exactly
  // those ABIs: one more and the staged host has no shim to load it, one fewer
  // and the AAR advertises an ABI no consumer can complete.
  @Test
  fun thePluginSupportsExactlyTheAbisTheAarShipsAShimFor() {
    val hostModule = File(requireNotNull(System.getProperty("swifttui.hostModuleDir")))

    val gradleAbis = Regex("""val shimAbis = listOf\(([^)]*)\)""")
      .find(File(hostModule, "build.gradle.kts").readText())
      ?.groupValues?.get(1)
      ?.let { Regex("\"([^\"]+)\"").findAll(it).map { match -> match.groupValues[1] }.toList() }
    val ndkBuildAbis = File(hostModule, "src/main/jni/Application.mk").readLines()
      .firstOrNull { it.startsWith("APP_ABI") }
      ?.substringAfter(":=")?.trim()?.split(Regex("\\s+"))

    assertEquals("swift-tui-host/build.gradle.kts shimAbis", SwiftTuiAndroidAbi.supportedNames, gradleAbis)
    assertEquals("swift-tui-host Application.mk APP_ABI", SwiftTuiAndroidAbi.supportedNames, ndkBuildAbis)
  }
}
