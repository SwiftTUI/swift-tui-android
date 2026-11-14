plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.plugin.compose")
  `maven-publish`
}

val userHome = providers.systemProperty("user.home").get()
val androidSdkDir = providers.environmentVariable("ANDROID_HOME")
  .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
  .orElse("$userHome/Library/Android/sdk")
val ndkVersionPin = "30.0.16248370"

// The JNI shim is pure C++17 (NDK only — no Swift toolchain). Gate the native
// build on NDK presence so a JVM-only `testDebugUnitTest` gate can configure and
// run without it, mirroring the app's `swiftAndroidToolingAvailable` gate.
val ndkAvailable = androidSdkDir.map { file("$it/ndk/$ndkVersionPin").exists() }

// The ABIs the AAR ships its JNI shim for. Keep this, src/main/jni/Application.mk
// (APP_ABI) and the Gradle plugin's SwiftTuiAndroidAbi table equal: the plugin
// stages the Swift host and runtime for exactly these ABIs, and its unit tests
// fail when the three lists differ. The shim is about 160 KB per ABI (under
// 60 KB compressed) and the Swift runtime is not in the AAR, so an ABI a
// consumer does not select costs their APK nothing: their abiFilters drop it.
val shimAbis = listOf("arm64-v8a", "x86_64")

android {
  namespace = "sh.swifttui.android.host"

  compileSdk {
    version = release(37) {
      minorApiLevel = 1
    }
  }

  if (ndkAvailable.get()) {
    ndkVersion = ndkVersionPin
  }

  defaultConfig {
    minSdk = 28
    testInstrumentationRunner = "sh.swifttui.android.host.ClipQualificationRunner"

    ndk {
      abiFilters += shimAbis
    }

    consumerProguardFiles("consumer-rules.pro")
  }

  if (ndkAvailable.get()) {
    externalNativeBuild {
      ndkBuild {
        path = file("src/main/jni/Android.mk")
      }
    }
  }

  buildFeatures {
    compose = true
  }

  // Publish a single release variant of the AAR, plus a sources jar.
  publishing {
    singleVariant("release") {
      withSourcesJar()
    }
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = false
      // -PswiftTUIRowDecoding=rebuild runs the JVM suite on the decoder
      // session's earlier row-decoding path instead of the default one.
      all { test ->
        providers.gradleProperty("swiftTUIRowDecoding").orNull?.let {
          test.systemProperty("sh.swifttui.android.host.rowDecoding", it)
        }
      }
    }
  }
}

dependencies {
  // `api` for the Compose types that appear in this library's public surface
  // (e.g. `Modifier` on SwiftTUIHostView); `implementation` for the rest.
  api(platform("androidx.compose:compose-bom:2026.08.00"))
  api("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.ui:ui-graphics")

  testImplementation("junit:junit:4.13.2")
  // The real org.json, shadowing the stubbed android.jar copy so the frame
  // parser can be exercised in plain JVM unit tests.
  testImplementation("org.json:json:20260719")
}

publishing {
  publications {
    register<MavenPublication>("release") {
      artifactId = "android-host"
      // The `release` software component is created by AGP late.
      afterEvaluate { from(components["release"]) }
      pom {
        name = "SwiftTUI Android host"
        description =
          "Compose host that embeds a SwiftTUI view in an Android app (JNI shim " +
          "+ renderer). Small: the Swift runtime is plugin-copied per consumer."
        url = "https://swifttui.sh"
        licenses {
          license {
            name = "MIT License"
            url = "https://github.com/SwiftTUI/swift-tui-android/blob/main/LICENSE"
          }
        }
        developers {
          developer {
            name = "OPTIONAL.DEV"
            url = "https://swifttui.sh"
          }
        }
        scm {
          url = "https://github.com/SwiftTUI/swift-tui-android"
          connection = "scm:git:https://github.com/SwiftTUI/swift-tui-android.git"
          developerConnection = "scm:git:git@github.com:SwiftTUI/swift-tui-android.git"
        }
      }
    }
  }
  // The githubPages repository is configured centrally in the root build for
  // every maven-publish module (the AAR and the plugin marker).
}
