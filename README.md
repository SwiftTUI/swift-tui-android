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
plugins { id("sh.swifttui.android") version "0.0.23" }
dependencies { implementation("sh.swifttui:android-host:0.0.23") }
swiftTuiAndroidHost { productName = "MyAppHost" }  // your SwiftPM product
```

Then you write exactly two app-side pieces:

**1. A one-screen Swift entry** (a SwiftPM product named to match `productName`)
that wraps your root `View` and exposes the fixed create symbol:

```swift
import SwiftTUI
import SwiftTUIAndroidHost   // tagged HTTPS dependency on SwiftTUI/swift-tui

private struct MyApp: App {
  var body: some Scene { WindowGroup { MyRootView() } }
}

@_cdecl("swift_tui_android_create_host")
public func swift_tui_android_create_host() -> Int64 {
  MainActor.assumeIsolated {
    (try? AndroidHostHandleRegistry.register(AndroidHostSceneHost(app: MyApp()))) ?? 0
  }
}
```

**2. Mount the host** in Compose:

```kotlin
setContent { SwiftTUIHostView(state = rememberSwiftTUIHostState()) }
```

The plugin cross-builds your Swift product for `arm64-v8a`, renames it to the
canonical `libswift_tui_app_host.so`, and merges it + the Swift runtime into your
APK's `jniLibs`. (Requires Swift 6.3.x + the Swift Android SDK to build your host
`.so`.)

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
