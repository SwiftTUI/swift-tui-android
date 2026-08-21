# SwiftTUI for Android

**Mount your SwiftTUI app inside a Jetpack Compose UI: the same `App`, `@State`, and `@FocusState` you run in a terminal, now a native Compose view.**

![Swift 6.3](https://img.shields.io/badge/Swift-6.3-F05138?logo=swift&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Android%20(minSdk%2028)-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/status-0.9.5%20pre--release-DAA520)
![License](https://img.shields.io/badge/license-MIT-3DA639)

`swift-tui-android` is the Android host for [SwiftTUI](https://swifttui.sh). The
Gradle plugin cross-builds your Swift-authored view tree to a native `.so`.
The `SwiftTUIHostView` composable renders it. The same `View` tree can run in a
terminal, a WASI bundle, a local WebHost, a native SwiftUI surface, or Android.

**See it on a device:** the counter demo's
[`AndroidExample`](https://github.com/SwiftTUI/swift-tui-counter-demo/tree/main/AndroidExample)
consumes these exact artifacts — plugin `0.9.5` and
`sh.swifttui:android-host:0.9.5` — and runs on an emulator or phone. It hosts
the same `CounterView` that the terminal, SwiftUI, and browser hosts run, so it
shows the one-source-many-hosts claim rather than describing it.

> Pre-1.0 (0.9.5 beta). Published via GitHub Pages until the Gradle
> Plugin Portal / Maven Central graduation.

> Android is a **0.9 preview, arm64-only support tier**: `arm64-v8a`, API 28+,
> NDK `27.3.13750724`, Swift 6.3.x, and the Swift Android SDK through the
> published AAR/Gradle-plugin packaging path. The lower-level host can
> cross-compile x86_64, but x86_64 packaging and IME marked/pre-edit
> composition are outside the 0.9 claim. The Compose accessibility overlay is
> one-way semantic presentation; TalkBack-origin focus and control actions do
> not route back into SwiftTUI.

## Why use it

- **One codebase, every surface.** Author your UI once against SwiftTUI and host
  it on Android next to the terminal, WASI, WebHost, and Apple-platform hosts,
  so a new platform costs you a host module, not a rewrite.
- **Drop-in Compose.** `SwiftTUIHostView` is an ordinary composable. Mount it
  with one `setContent { … }` call, with no `AndroidView` interop glue to write.
- **The build is handled for you.** The Gradle plugin cross-compiles your Swift
  host product for `arm64-v8a`, renames the output canonically, and stages it
  plus the Swift runtime for your APK's `jniLibs`. You declare the source set
  and the SwiftPM product; the plugin does the toolchain plumbing.
- **Lean APK.** The host AAR is a Compose view plus a JNI shim and does **not**
  bundle the Swift runtime; the plugin supplies that from your Swift Android
  SDK, so your APK ships exactly one copy.

## What this repo publishes

| Artifact | Coordinates | What it is |
| --- | --- | --- |
| **Host library (AAR)** | `sh.swifttui:android-host` | A Compose `SwiftTUIHostView` + the JNI shim that bridges to the Swift `SwiftTUIAndroidHost` C ABI. Runtime not bundled. |
| **Gradle plugin** | `sh.swifttui.android` | Cross-builds your Swift host product to an Android `.so` and copies it + the Swift runtime (from your Swift Android SDK) into your app's `jniLibs`. |

It is the Android sibling of
[`swift-tui-swiftui`](https://github.com/SwiftTUI/swift-tui-swiftui) (native
macOS/iOS host) and [`swift-tui-web`](https://github.com/SwiftTUI/swift-tui-web)
(browser host). The runtime it drives lives in
[`swift-tui`](https://github.com/SwiftTUI/swift-tui).

## Using it (consumer)

Four files. The layout below is the plugin's default; see
[Where the Swift package goes](#where-the-swift-package-goes) to put it elsewhere.

```
MyApp/
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/main/kotlin/…/MainActivity.kt
└── SwiftPackage/            ← sibling of the app module
    ├── Package.swift
    └── Sources/MyAppHost/MyApp.swift
```

The counter demo's
[`AndroidExample`](https://github.com/SwiftTUI/swift-tui-counter-demo/tree/main/AndroidExample)
is this layout filled in — plus the ordinary Android scaffolding every app has
(root `build.gradle.kts`, `gradle.properties`, a manifest, the wrapper). Read it
alongside the four steps below if you would rather start from a working copy
than from a blank directory.

**1. `settings.gradle.kts`** — register the Pages repo for BOTH the plugin and
the AAR (until the Plugin Portal / Maven Central graduation). `pluginManagement`
must come first and is evaluated on its own, so the URL is repeated rather than
shared through a `val`:

```kotlin
pluginManagement {
  repositories {
    gradlePluginPortal(); google(); mavenCentral()
    maven { url = uri("https://swifttui.github.io/swift-tui-android") }
  }
}
dependencyResolutionManagement {
  repositories {
    google(); mavenCentral()
    maven { url = uri("https://swifttui.github.io/swift-tui-android") }
  }
}

rootProject.name = "MyApp"
include(":app")
```

**2. `app/build.gradle.kts`.** The plugin *stages* the Swift host library and
runtime into `build/generated/swiftJniLibs`, but packaging them is your app's
decision — so the `jniLibs` source set and `useLegacyPackaging` below are
required, not optional. Leave the `jniLibs` source set out and the APK would
still build, install, and launch — with no Swift inside it, `dlopen` failing to
logcat and the view staying blank. The plugin's `verifySwiftAndroidJniLibs`
check fails the build with that explanation instead.

```kotlin
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
  id("sh.swifttui.android") version "0.9.5"
}

android {
  namespace = "com.example.myapp"

  // 37.1 or newer: both this AAR and Compose declare minCompileSdk 37.
  compileSdk {
    version = release(37) { minorApiLevel = 1 }
  }

  // Pin the NDK rather than taking AGP's default: it strips the packaged Swift
  // `.so` files, and a default your machine has not installed fails the build.
  ndkVersion = "27.3.13750724"

  defaultConfig {
    applicationId = "com.example.myapp"
    minSdk = 28
    targetSdk = 36

    // arm64-v8a is the 0.9 support tier. Without this the APK advertises ABIs
    // it has no Swift runtime for, and installs-then-blanks on those devices.
    ndk { abiFilters += "arm64-v8a" }
  }

  // Where the plugin stages libswift_tui_app_host.so and the Swift runtime.
  sourceSets["main"].jniLibs.directories.add(
    layout.buildDirectory.dir("generated/swiftJniLibs").get().asFile.path
  )

  packaging {
    jniLibs {
      // Extract the Swift runtime at install time so dlopen resolves it.
      useLegacyPackaging = true
    }
  }

  buildFeatures { compose = true }
}

// The one per-app Swift setting: which SwiftPM product to cross-build.
swiftTuiAndroidHost { productName = "MyAppHost" }

dependencies {
  implementation("sh.swifttui:android-host:0.9.5")
  implementation(platform("androidx.compose:compose-bom:2026.08.00"))
  implementation("androidx.activity:activity-compose:1.13.0")
}
```

**3. `SwiftPackage/Package.swift`.** The product must be a **dynamic** library:
the JVM owns the process and the Swift side is loaded with `dlopen`, so there is
no Swift entry point. SwiftPM names the output after the product, and the plugin
renames `libMyAppHost.so` to the canonical `libswift_tui_app_host.so` on the way
into the APK.

Author against **`SwiftTUIRuntime`**, not the `SwiftTUI` umbrella — the umbrella
pulls in terminal-only PTY primitives that do not compile for Android.

```swift
// swift-tools-version: 6.3
import PackageDescription

let package = Package(
  name: "my-app-host",
  platforms: [.macOS(.v15), .iOS(.v18)],
  products: [
    .library(name: "MyAppHost", type: .dynamic, targets: ["MyAppHost"])
  ],
  dependencies: [
    .package(url: "https://github.com/SwiftTUI/swift-tui.git", exact: "0.9.5")
  ],
  targets: [
    .target(
      name: "MyAppHost",
      dependencies: [
        .product(name: "SwiftTUIRuntime", package: "swift-tui"),
        .product(name: "SwiftTUIAndroidHost", package: "swift-tui"),
      ]
    )
  ],
  swiftLanguageModes: [.v6]
)
```

**4. The Swift entry point** — your root `View`, wrapped in an `App`, behind the
one fixed symbol the AAR's JNI shim looks up:

```swift
import SwiftTUIRuntime
import SwiftTUIAndroidHost

struct MyRootView: View {
  var body: some View { Text("Hello from SwiftTUI") }
}

struct MyApp: App {
  var body: some Scene { WindowGroup { MyRootView() } }
}

@_cdecl("swift_tui_android_create_host")
public func swift_tui_android_create_host() -> Int64 {
  MainActor.assumeIsolated {
    do {
      let host = try AndroidHostSceneHost(app: MyApp())
      return AndroidHostHandleRegistry.register(host)
    } catch {
      return 0
    }
  }
}
```

**Mount the host** in Compose:

```kotlin
setContent { SwiftTUIHostView(state = rememberSwiftTUIHostState()) }
```

During the build, the plugin cross-compiles `MyAppHost` for `arm64-v8a`, renames
the result to `libswift_tui_app_host.so`, and stages it plus the Swift runtime
into `build/generated/swiftJniLibs` for the source set declared above.

### Where the Swift package goes

`swiftTuiAndroidHost.packageDirectory` defaults to `../SwiftPackage`, relative to
the app module — the `MyApp/SwiftPackage/` in the layout above. Point it anywhere
else, and add any sources outside the package that should retrigger the Swift
cross-build:

```kotlin
swiftTuiAndroidHost {
  productName = "MyAppHost"
  packageDirectory = layout.projectDirectory.dir("../swift/Host")
  additionalSwiftSources.from(layout.projectDirectory.dir("../shared/Sources"))
}
```

## Requirements

- Install the Android SDK Platform **37.1** and NDK `27.3.13750724` for the JNI
  shim. Use `minSdk 28` and `compileSdk 37.1` — both this AAR and Compose
  `1.12.0` declare `minCompileSdk 37`, so a lower `compileSdk` fails
  `checkAarMetadata`.
- Use **AGP 9.1.0–9.2.1**. Compose `1.12.0` requires 9.1.0 or newer, and Android
  Studio 2026.1 refuses to sync a project on 9.3.x. Building one SDK ahead of
  the plugin is supported but warned about; silence it with
  `android.suppressUnsupportedCompileSdk=37.1` in `gradle.properties`.
- Install Swift 6.3.x and the Swift Android SDK to cross-compile the host `.so`.
  The plugin looks for the bundle under
  `~/Library/org.swift.swiftpm/swift-sdks/`; elsewhere (Linux, or a custom
  install) point `SWIFT_ANDROID_SDK_BUNDLE` at the `.artifactbundle`.
- Install the toolchains as described in the
  [SwiftTUI documentation](https://swifttui.sh). The repository does not vendor
  these toolchains.

## Building locally

Maintainer build/test commands live in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). This repository builds and tests the
AAR and the plugin; it does not cross-compile Swift. The full host build — Swift
cross-compile, packaging, and an emulator install — lives in the counter demo's
[`AndroidExample`](https://github.com/SwiftTUI/swift-tui-counter-demo/tree/main/AndroidExample),
which consumes these artifacts as a published dependency:

```bash
git clone https://github.com/SwiftTUI/swift-tui-counter-demo.git
cd swift-tui-counter-demo/AndroidExample
./gradlew :app:installDebug
```

## Documentation & support

- **Project site & framework API reference:** <https://swifttui.sh/docs/documentation/>.
  The `SwiftTUIAndroidHost` SwiftPM product's reference is published there
  with the framework DocC. This README is the reference for the Kotlin/Gradle
  side (`SwiftTUIHostView`, the `swiftTuiAndroidHost { }` extension).
- **The framework:** [`SwiftTUI/swift-tui`](https://github.com/SwiftTUI/swift-tui),
  the authoring API, products, and platform matrix.
- **Other hosts:** [`swift-tui-swiftui`](https://github.com/SwiftTUI/swift-tui-swiftui)
  (native macOS/iOS) and [`swift-tui-web`](https://github.com/SwiftTUI/swift-tui-web)
  (browser).
- **Questions & issues:** <https://github.com/SwiftTUI/swift-tui-android/issues>

## License

MIT; see [LICENSE](LICENSE).
