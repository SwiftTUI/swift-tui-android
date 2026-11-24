# SwiftTUI for Android

**Mount your SwiftTUI app inside a Jetpack Compose UI: the same `App`, `@State`, and `@FocusState` you run in a terminal, now a native Compose view.**

![Swift 6.4](https://img.shields.io/badge/Swift-6.4-F05138?logo=swift&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Android%20(minSdk%2028)-3DDC84?logo=android&logoColor=white)
![Status](https://img.shields.io/badge/status-0.22.1%20pre--release-DAA520)
![License](https://img.shields.io/badge/license-MIT-3DA639)

`swift-tui-android` is the Android host for [SwiftTUI](https://swifttui.sh). The
Gradle plugin cross-builds your Swift-authored view tree to a native `.so`.
The `SwiftTUIHostView` composable renders it. The same `View` tree can run in a
terminal, a WASI bundle, a local WebHost, a native SwiftUI surface, or Android.

Host-wire admission caps records at 4 MiB of UTF-8 and grids at 1,024 cells
per axis / 65,536 cells total. Oversized input preserves the last valid frame
and requests one keyframe repair; size-query limits apply before buffer
allocation. Surface records must also be strict JSON. Before parsing, the
decoder checks the RFC 8259 grammar: string keys, each followed by one `:`,
one `,` between members and between elements, matching brackets, and only
whitespace after the object. Outside strings it accepts only `true`, `false`,
`null` and RFC 8259 numbers. It refuses other forms, such as `NaN`,
`Infinity`, unquoted words, comments, single quotes, trailing commas and a
second value, in the same way. See the shared [allocation policy](https://github.com/SwiftTUI/swift-tui/blob/main/docs/HOST-WIRE-CONTRACT.md#allocation-budgets)
for metadata, framing, and bitmap limits.

**See it on a device:** the counter demo's
[`AndroidExample`](https://github.com/SwiftTUI/swift-tui-counter-demo/tree/main/AndroidExample)
consumes these exact artifacts — plugin `0.22.1` and
`sh.swifttui:android-host:0.22.1` — and runs on an emulator or phone. It hosts
the same `CounterView` that the terminal, SwiftUI, and browser hosts run, so it
shows the one-source-many-hosts claim rather than describing it.

> Pre-1.0 (0.22.1 beta). Published via GitHub Pages until the Gradle
> Plugin Portal / Maven Central graduation.

> Android is a **0.9 preview**: API 28+, NDK `30.0.16248370`, Swift 6.4.x, and
> the Swift Android SDK through the AAR/Gradle-plugin packaging path.
> `arm64-v8a` is the default ABI and the one qualified on a device. `x86_64` is
> an opt-in second ABI (see [Supported ABIs](#supported-abis)): it is packaged
> and its payload is checked, but it has not yet been run on an x86_64 device
> or emulator. With a Swift host library that carries the `textInput`
> operation, the invisible editor mirrors the focused `TextField` or
> `TextEditor` and sends each input-method edit whole: text, UTF-16 selection
> and composing range. Secure fields and older host libraries keep
> committed-text input, where pre-edit text is sent as typed. The
> accessibility overlay uses a native virtual
> hierarchy inside Compose. With a runtime advertising semantic actions, it
> routes accessibility focus, activation, adjustment and text edits through JNI;
> runtime frames remain authoritative. Runtimes older than 0.15.0 supply
> presentation only. Connected TalkBack acceptance of the new action path has
> not yet been established.

## Why use it

- **One codebase, every surface.** Author your UI once against SwiftTUI and host
  it on Android next to the terminal, WASI, WebHost, and Apple-platform hosts,
  so a new platform costs you a host module, not a rewrite.
- **Drop-in Compose.** `SwiftTUIHostView` is an ordinary composable. Mount it
  with one `setContent { … }` call, with no `AndroidView` interop glue to write.
- **The build is handled for you.** The Gradle plugin cross-compiles your Swift
  host product for each ABI you select (`arm64-v8a` unless you say otherwise),
  renames the output canonically, and stages it plus the Swift runtime for your
  APK's `jniLibs`. You declare the source set and the SwiftPM product; the
  plugin does the toolchain plumbing.
- **Lean APK.** The host AAR is a Compose view plus a JNI shim and does **not**
  bundle the Swift runtime; the plugin supplies that from your Swift Android
  SDK, so your APK ships exactly one copy per ABI.

## What this repo publishes

| Artifact | Coordinates | What it is |
| --- | --- | --- |
| **Host library (AAR)** | `sh.swifttui:android-host` | A Compose `SwiftTUIHostView` + the JNI shim that bridges to the Swift `SwiftTUIAndroidHost` C ABI, for each [supported ABI](#supported-abis). Runtime not bundled. |
| **Gradle plugin** | `sh.swifttui.android` | Cross-builds your Swift host product to an Android `.so` per selected ABI and copies it + the Swift runtime (from your Swift Android SDK) into your app's `jniLibs`. |

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
  id("sh.swifttui.android") version "0.22.1"
}

android {
  namespace = "com.example.myapp"

  // 37.1 or newer: both this AAR and Compose declare minCompileSdk 37.
  compileSdk {
    version = release(37) { minorApiLevel = 1 }
  }

  // Pin the NDK rather than taking AGP's default: it strips the packaged Swift
  // `.so` files, and a default your machine has not installed fails the build.
  ndkVersion = "30.0.16248370"

  defaultConfig {
    applicationId = "com.example.myapp"
    minSdk = 28
    targetSdk = 36

    // Package exactly the ABIs the plugin stages Swift for (arm64-v8a unless
    // swiftTuiAndroidHost.abis says otherwise). Without this the APK advertises
    // ABIs it has no Swift runtime for, and installs-then-blanks on those devices.
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
  implementation("sh.swifttui:android-host:0.22.1")
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
// swift-tools-version: 6.4
import PackageDescription

let package = Package(
  name: "my-app-host",
  platforms: [.macOS(.v15), .iOS(.v18)],
  products: [
    .library(name: "MyAppHost", type: .dynamic, targets: ["MyAppHost"])
  ],
  dependencies: [
    .package(url: "https://github.com/SwiftTUI/swift-tui.git", .upToNextMajor(from: "0.21.0"))
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

The JNI loader resolves the required host operations once, before installing the
executor and calling your factory. It checks the sized ABI-info export when
present (ABI 1, 64-bit handles/scalars and 32-bit counts). Pre-query libraries
are accepted only when every required operation exists. Missing optional
operations remain unavailable. Startup failures raise `UnsatisfiedLinkError`
with an `android.abi.*` code and the missing requirement. The fixed-width header
is [`swift_tui_android.h`](swift-tui-host/src/main/jni/swift_tui_android.h).

**Mount the host** in Compose:

```kotlin
setContent { SwiftTUIHostView(state = rememberSwiftTUIHostState()) }
```

During the build, the plugin cross-compiles `MyAppHost` for `arm64-v8a`, renames
the result to `libswift_tui_app_host.so`, and stages it plus the Swift runtime
into `build/generated/swiftJniLibs/arm64-v8a` for the source set declared above.

### Supported ABIs

| Android ABI | Swift SDK triple | Swift runtime (in the Swift Android SDK) | NDK `libc++_shared.so` |
| --- | --- | --- | --- |
| `arm64-v8a` (default) | `aarch64-unknown-linux-android28` | `swift-resources/usr/lib/swift-aarch64/android` | `sysroot/usr/lib/aarch64-linux-android` |
| `x86_64` | `x86_64-unknown-linux-android28` | `swift-resources/usr/lib/swift-x86_64/android` | `sysroot/usr/lib/x86_64-linux-android` |

The AAR carries its JNI shim for both. The plugin builds and stages Swift only
for the ABIs in `swiftTuiAndroidHost.abis`, which defaults to `arm64-v8a`, so an
app that selects nothing builds exactly what it did before. To add `x86_64`
(an emulator on an Intel or AMD host, or a CI runner), name both ABIs in both
places:

```kotlin
android {
  defaultConfig {
    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
  }
}

swiftTuiAndroidHost {
  productName = "MyAppHost"
  abis = setOf("arm64-v8a", "x86_64")
}
```

`abis` is the complete list, not an addition to the default. Each ABI is a
separate Swift cross-build (`buildSwiftAndroidArm64V8a`,
`buildSwiftAndroidX86_64`) staged into its own `jniLibs/<abi>` directory, and one
more copy of the Swift runtime in the APK: the counter demo's debug APK grows
from about 77 MB to about 132 MB with both. `arm64-v8a` builds in SwiftPM's
usual `SwiftPackage/.build`; `x86_64` builds in its own scratch directory inside
it, `.build/android-x86_64`, because SwiftPM cannot build two triples
incrementally in one.

The build fails, rather than producing an APK that installs and shows a blank
view, when:

- `abis` is empty or names an ABI outside the table (`armeabi-v7a`, `x86`,
  `riscv64`);
- the installed Swift Android SDK or NDK has no runtime for a selected ABI;
- `defaultConfig.ndk.abiFilters` names an ABI that `abis` does not. The reverse
  is allowed: a flavor or build type may package fewer ABIs than are staged.

> `abis` and the `x86_64` shim are in this source tree. The published `0.22.0`
> plugin and AAR predate them: they are `arm64-v8a` only and have no `abis`
> property.

### Links

The runtime decides when a `Link` is activated, so a custom `OpenLinkAction`
and a disabled link behave as authored. The host opens the destination once,
through `LocalUriHandler`, and only for an `http`, `https`, `mailto` or `tel`
URI. Anything else, and a destination no app handles, opens nothing and leaves
the app running. Pass your own `SwiftTUILinkDispatcher` to
`rememberSwiftTUIHostState(links)` to change the allowed schemes, open links
your own way, or observe each outcome. A Swift host library from swift-tui
0.22.1 or earlier keeps the earlier behavior, where the host opens the
hyperlink under a tap. See [docs/LINKS.md](docs/LINKS.md) for both routes, the
full URI policy and the limitations.

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

## Importing a document

A SwiftTUI `.dropDestination` receives filesystem paths. An Android document is
a `content://` URI, which is not a path. `SwiftTUIHostState.importDocument`
bridges the two for one document from the system document picker: it copies the
document into a file the host owns and delivers that file's path to the running
app as one file drop. The app shows the picker; the host does the import.

```kotlin
val hostState = rememberSwiftTUIHostState()
val policy = remember { SwiftTUIDocumentImportPolicy() }
val scope = rememberCoroutineScope()
val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  scope.launch {
    when (val result = hostState.importDocument(uri, policy)) {
      is SwiftTUIDocumentImportResult.Delivered -> { /* result.document */ }
      SwiftTUIDocumentImportResult.Cancelled -> { /* the picker was dismissed */ }
      is SwiftTUIDocumentImportResult.Rejected -> { /* result.reason */ }
    }
  }
}

// From a button, a menu, wherever the app offers it:
picker.launch(policy.acceptedMimeTypes.toTypedArray())
```

Call `importDocument` on the main thread, as for the rest of `SwiftTUIHostState`.
The provider and the disk are read on `Dispatchers.IO`; the call suspends until
the document is delivered or refused.

**What is imported.** One `content://` URI per call, read through the access the
picker granted. Other schemes are refused unread. The host takes no persistable
URI permission: it copies the bytes while the grant is live, and the app only
ever sees the copy. A stream that cannot seek or does not report its size is
fine; the copy is sequential.

**Policy.** `SwiftTUIDocumentImportPolicy` bounds the copy:

| Property | Default | Meaning |
| --- | --- | --- |
| `maxDocumentBytes` | 16 MiB | Largest document. Enforced on the bytes read, so a missing or wrong reported size cannot exceed it; at most one byte past the cap is read. |
| `maxRetainedBytes` | 64 MiB | Most imported bytes one host keeps on disk. An import that would exceed it is refused; earlier imports stay valid. |
| `acceptedMimeTypes` | `text/*`, `application/json`, `image/png`, `image/jpeg`, `image/gif` | MIME allow-list (exact types, `type/*`, or `*/*`). Pass it to the picker too; the import checks again because a picker filter is advisory. A provider that reports no type, or `application/octet-stream`, is typed by the display name's extension. |

**Outcomes.** Every call ends in exactly one of these, and only `Delivered`
leaves a file behind or sends anything to the app:

| Result | When |
| --- | --- |
| `Delivered(document)` | The whole document was copied and its path queued for the app once. `document` carries the path, display name, MIME type and size. |
| `Cancelled` | The URI was `null`: the picker was dismissed. |
| `Rejected(UNSUPPORTED_SOURCE)` | The URI is not a `content://` URI. |
| `Rejected(UNSUPPORTED_TYPE)` | The type is outside the allow-list. The content is not opened. |
| `Rejected(TOO_LARGE)` | The document exceeds `maxDocumentBytes`. |
| `Rejected(STORAGE_BUDGET_EXCEEDED)` | Keeping it would exceed `maxRetainedBytes`. |
| `Rejected(PERMISSION_DENIED)` | Access was never granted, or was revoked, before or during the read. |
| `Rejected(NOT_FOUND)` | The document no longer exists or cannot be opened as a byte stream. |
| `Rejected(IO_FAILURE)` | The provider or local storage failed, or the stream ended before its reported size. |
| `Rejected(IMPORT_IN_PROGRESS)` | Another import on this host has not finished. Imports never overlap. |
| `Rejected(HOST_NOT_RUNNING)` | The host is not running, or stopped before delivery. |

Cancelling the calling coroutine also leaves no file and delivers nothing. Later
releases may add rejection reasons; keep an `else` branch when switching on
`reason`.

**Name and type.** The copy keeps the provider's display name as its file name,
reduced to one safe path component: directory parts, control and
direction-override characters and leading dots are removed, and the name is
capped at 120 UTF-8 bytes with its extension kept. Spaces, quotes and non-ASCII
letters survive. A name with no extension gains the one registered for the MIME
type, because a path carries its type only in its extension.

**Where the copy lives, and for how long.** Under the app's cache directory, at
`swifttui-imports/<host session>/<n>/<display name>`. It is written to a
`.partial` file and renamed into place, so a delivered path always names a
complete file. The path stays valid until the host state is destroyed
(`rememberSwiftTUIHostState` does that when it leaves the composition) or the
app calls `clearImportedDocuments()`; the host deletes the files then. Files a
killed process left behind are deleted when the next host starts. The operating
system may also evict cache files when storage runs short. Importing unchanged
content under the same name again delivers the retained file's path a second
time and keeps one copy. An app that hosts SwiftTUI in more than one process
shares this directory between them; that arrangement is not supported.

**Delivery.** The path reaches the runtime as the `file://` paste a terminal
produces for a dropped file, over the existing input channel. As on a terminal,
`Delivered` does not report whether a drop destination took the path, and a
drop that no destination claims falls through to the runtime's text-paste
handling.

Selecting several documents, share-sheet (`ACTION_SEND`) intake and platform
drag-and-drop are not part of this route.

## Clipboard

**Copy.** On every frame poll the host asks the runtime for the app's latest
clipboard write and forwards it to `ClipboardManager`, once per write, as the
exact UTF-8 text. A write that a destroyed host still held is never delivered.
An app clears the clipboard by writing an empty string, and the host then
removes the primary clip. That needs a `swift-tui` host library with the take
operation of
[native ABI 1 minor 1](https://github.com/SwiftTUI/swift-tui/blob/main/Contracts/1/profiles/android-abi.md).
An older library refuses an empty write, so the app's clipboard action returns
`false` and the clipboard keeps its content.

**Paste.** Ctrl+V and Shift+Insert read the system clipboard and send its text
to the runtime as one bracketed paste. Control bytes other than tab and newline
are removed first, so pasted text cannot carry an escape sequence or the
paste-end marker. Paste is text only: the text of the primary clip's first
item, which for an HTML clip is its plain-text alternative. An absent clip, an
empty text, and a clip whose first item has no text (a URI or an Intent) paste
nothing. The host never resolves a content URI or an Intent into text.

**Hardware chords.** Ctrl with a letter reaches the runtime as the control byte
a terminal sends (Ctrl+A is `0x01`), so Ctrl+A, Ctrl+C and Ctrl+X select, copy
and cut in a focused text control. The host claims these chords and the paste
shortcuts before the invisible text field that receives soft-keyboard input
can act on them. Ctrl+C with nothing to copy reaches the runtime's exit key
binding, which a hosted session refuses: the session keeps running and the
runtime logs `lifecycle.userExitUnsupported`.

The host has no native selection handles or menus. Text that a soft keyboard
commits, including text from a keyboard's own clipboard strip, arrives as typed
input: it is not a bracketed paste and is not filtered.

## Requirements

- Install the Android SDK Platform **37.1** and NDK `30.0.16248370`: it strips
  the packaged libraries and supplies each ABI's `libc++_shared.so`. Use
  `minSdk 28` and `compileSdk 37.1` — both this AAR and Compose
  `1.12.0` declare `minCompileSdk 37`, so a lower `compileSdk` fails
  `checkAarMetadata`.
- Use **AGP 9.1.0–9.2.1**. Compose `1.12.0` requires 9.1.0 or newer, and Android
  Studio 2026.1 refuses to sync a project on 9.3.x. Building one SDK ahead of
  the plugin is supported but warned about; silence it with
  `android.suppressUnsupportedCompileSdk=37.1` in `gradle.properties`.
- Install Swift 6.4.x and the Swift Android SDK to cross-compile the host `.so`.
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

The decoder session keeps the decoded cells of each wire row and reuses every
row a delta does not replace, restyle or relink. A frame's `cells` list is a
read-only view over those rows, and the renderer's row index for partial
painting is the same rows; a published frame never changes. Detailed
column damage takes precedence over the decoder's dirty-row summary; a row
with no column details repaints at full width. Image-bearing frames retain the
conservative full repaint behavior. Surface grid dimensions must be nonnegative
integers representable by Kotlin `Int`; malformed dimensions are rejected
before changing a decoder baseline. These are structural checks, not device
bitmap-allocation limits.

Partial Canvas repaints clip to the union of damaged column ranges, preserving
unchanged gaps and the clean portion of any overlapping wide cell. Glyph and
decoration ink stays inside its declared cell span on both full and partial
paints. Italic or fallback-font overhang outside that span is now clipped even
on full paints; this is a rendering behavior change with no public API change.

## Version compatibility

See the [compatibility policy](docs/COMPATIBILITY.md) for supported major lines,
independent dependency adoption, and experimental interfaces.
