# swift-tui-android

The Android host for [SwiftTUI](https://swifttui.sh) — embed a SwiftTUI view in
an Android/Jetpack Compose app.

This repo publishes two artifacts:

| Artifact | Coordinates | What it is |
| --- | --- | --- |
| **Host library (AAR)** | `sh.swifttui:android-host` | A Compose `SwiftTUIHostView` + the JNI shim that bridges to the Swift `SwiftTUIAndroidHost` C ABI. Small — it does **not** bundle the Swift runtime. |
| **Gradle plugin** | `sh.swifttui.android` | Cross-builds your Swift host product to an Android `.so` and copies it + the Swift runtime (from your Swift Android SDK) into your app's `jniLibs`. |

## Using it (consumer)

```kotlin
// settings.gradle.kts — add the Pages repo for BOTH the plugin and the AAR
// (until the Plugin Portal / Maven Central graduation):
val swiftTuiRepo = "https://swifttui.github.io/swift-tui-android"
pluginManagement {
  repositories {
    gradlePluginPortal(); google(); mavenCentral()
    maven { url = uri(swiftTuiRepo) }
  }
}
dependencyResolutionManagement {
  repositories {
    google(); mavenCentral()
    maven { url = uri(swiftTuiRepo) }
  }
}

// app/build.gradle.kts:
plugins { id("sh.swifttui.android") version "0.0.19" }
dependencies { implementation("sh.swifttui:android-host:0.0.19") }
swiftTuiAndroidHost { productName = "MyAppHost" }  // your SwiftPM product
```

Write a one-screen Swift entry (`@_cdecl("swift_tui_android_create_host")` over
your root `View`), host it with `SwiftTUIHostView()`. See the consumer guide in
the SwiftTUI docs.

## Building locally

```bash
./gradlew :swift-tui-host:testDebugUnitTest   # JVM unit tests (NDK-free)
./gradlew :swift-tui-host:assembleRelease      # the AAR
./gradlew publishToMavenLocal                  # AAR + plugin into ~/.m2
```

The full host build (Swift cross-compile + emulator) lives in the SwiftTUI
example app (`swift-tui-examples/AndroidGallery`), which consumes these artifacts.

## Requirements

- Android SDK + NDK `27.3.13750724` (for the JNI shim).
- Swift 6.3.x + the Swift Android SDK (consumers only — to cross-build their host).
- Toolchains are not vendored; install per the SwiftTUI docs.
