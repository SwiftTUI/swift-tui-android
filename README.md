# SwiftTUI for Android

**Embed a SwiftTUI view in an Android / Jetpack Compose app.**

![Swift 6.3](https://img.shields.io/badge/Swift-6.3-F05138?logo=swift&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Android%20(minSdk%2028)-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/status-0.0.27%20alpha-DAA520)
![License](https://img.shields.io/badge/license-MIT-3DA639)

`swift-tui-android` is the Android host for [SwiftTUI](https://swifttui.sh). It
cross-builds your Swift-authored UI to a native `.so`, bridges it through a thin
JNI shim, and renders it inside a Jetpack Compose `@Composable` — so the same
view code you run in a terminal, in the browser, and in a native Apple app also
runs on Android.

## Why use it

- **One codebase, every surface.** Author your UI once against SwiftTUI and host
  it on Android alongside the terminal, browser, and Apple-platform hosts. The
  [`AndroidGallery`](https://github.com/SwiftTUI/swift-tui-examples/tree/main/AndroidGallery)
  example consumes these exact artifacts.
- **Drop-in Compose.** `SwiftTUIHostView` is an ordinary composable — mount it
  with one `setContent { … }` call.
- **The build is handled for you.** The Gradle plugin cross-compiles your Swift
  host product for `arm64-v8a`, names the output canonically, and merges it plus
  the Swift runtime into your APK's `jniLibs`. You write two small files; the
  plugin does the toolchain plumbing.
- **Lean library.** The host AAR is a Compose view plus a JNI shim — it does
  **not** bundle the Swift runtime, which the plugin supplies from your Swift
  Android SDK.

## What this repo publishes

| Artifact | Coordinates | What it is |
| --- | --- | --- |
| **Host library (AAR)** | `sh.swifttui:android-host` | A Compose `SwiftTUIHostView` + the JNI shim that bridges to the Swift `SwiftTUIAndroidHost` C ABI. Small — it does **not** bundle the Swift runtime. |
| **Gradle plugin** | `sh.swifttui.android` | Cross-builds your Swift host product to an Android `.so` and copies it + the Swift runtime (from your Swift Android SDK) into your app's `jniLibs`. |

It is the Android sibling of
[`swift-tui-swiftui`](https://github.com/SwiftTUI/swift-tui-swiftui) (native
SwiftUI host) and [`swift-tui-web`](https://github.com/SwiftTUI/swift-tui-web)
(browser host). The runtime it drives lives in
[`swift-tui`](https://github.com/SwiftTUI/swift-tui).

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
plugins { id("sh.swifttui.android") version "0.0.27" }
dependencies { implementation("sh.swifttui:android-host:0.0.27") }
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

## Requirements

- Android SDK + NDK `27.3.13750724` (for the JNI shim); `minSdk 28`.
- Swift 6.3.x + the Swift Android SDK (consumers only — to cross-build their host).
- Toolchains are not vendored; install per the
  [SwiftTUI docs](https://swifttui.sh).

## Building locally

```bash
./gradlew :swift-tui-host:testDebugUnitTest   # JVM unit tests (NDK-free)
./gradlew :swift-tui-host:assembleRelease      # the AAR
./gradlew publishToMavenLocal                  # AAR + plugin into ~/.m2
```

The full host build (Swift cross-compile + emulator) lives in the SwiftTUI
example app
([`swift-tui-examples/AndroidGallery`](https://github.com/SwiftTUI/swift-tui-examples/tree/main/AndroidGallery)),
which consumes these artifacts.

## Documentation & support

- **Project site & live API reference:** <https://swifttui.sh/docs/documentation/>
- **The framework:** [`SwiftTUI/swift-tui`](https://github.com/SwiftTUI/swift-tui)
  — the authoring API, products, and platform matrix.
- **Other hosts:** [`swift-tui-swiftui`](https://github.com/SwiftTUI/swift-tui-swiftui)
  (native macOS/iOS) and [`swift-tui-web`](https://github.com/SwiftTUI/swift-tui-web)
  (browser).
- **Questions & issues:** <https://github.com/SwiftTUI/swift-tui-android/issues>

## License

MIT — see [LICENSE](LICENSE).
