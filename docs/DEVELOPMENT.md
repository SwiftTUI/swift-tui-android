# Development

## Toolchains

The Android SDK plus NDK `30.0.16248370` (`minSdk 28`) build the JNI shim for
`arm64-v8a` and `x86_64`; Swift 6.4.x with the Swift Android SDK cross-compiles
the host `.so` for each ABI a consumer selects. The
repository does not vendor these toolchains. Run the JVM unit tests on JDK 21,
as CI does: the IME tests rely on `java.text.BreakIterator` returning extended
grapheme clusters, which JDKs before 20 do not. On a device, the platform ICU
supplies those boundaries.

## Build & test

```bash
./gradlew :swift-tui-host:testDebugUnitTest   # JVM unit tests (NDK-free)
./gradlew :android-plugin:check               # plugin tests + publication identity
./gradlew :swift-tui-host:assembleRelease     # the AAR
./gradlew publishToMavenLocal                 # AAR + plugin into ~/.m2
Scripts/native_gate.sh                    # the repo gate CI runs
```

`:android-plugin:check` runs the plugin in real Gradle builds (TestKit) against
a fake Swift toolchain: a `swiftly` script, an SDK bundle and an NDK whose
"libraries" are text files naming their triple. It covers ABI selection and
rejection, per-ABI staging, incremental behaviour, the skip when tooling is
absent, and configuration-cache reuse, without Swift or the NDK.

The decoder session reuses decoded rows across deltas. The earlier path, which
decodes every cell for every frame, stays selectable as the reference that
`SwiftTUIDecodedRowEquivalenceTest` compares against. Run the whole JVM suite on
it with `-PswiftTUIRowDecoding=rebuild`, or select it in any process with the
system property `sh.swifttui.android.host.rowDecoding=rebuild`.
`DecodedRowWorkloads` generates the fixed record streams used to measure this
path and `DecodedRowBenchmark` is their manual driver, for a desktop JVM or for
`app_process` on a device.

Those targets cover the Kotlin half only: this repository never cross-compiles
Swift, so a green `native_gate.sh` says nothing about the Swift toolchain.
Exercise the full path — Swift cross-compile, `jniLibs` staging, APK packaging,
and an emulator install — through the counter demo's
[`AndroidExample`](https://github.com/SwiftTUI/swift-tui-counter-demo/tree/main/AndroidExample),
which consumes the published AAR and plugin exactly as a third-party app does:

```bash
git clone https://github.com/SwiftTUI/swift-tui-counter-demo.git
cd swift-tui-counter-demo/AndroidExample
./gradlew :app:installDebug     # arm64-v8a device or emulator
```

That example is the canonical end-to-end check for a change to the AAR, the
Gradle plugin, or the Swift `SwiftTUIAndroidHost` product.

## ABIs

Three lists name the supported ABIs and must stay equal: `shimAbis` in
`swift-tui-host/build.gradle.kts`, `APP_ABI` in
`swift-tui-host/src/main/jni/Application.mk`, and the `SwiftTuiAndroidAbi` table
in the plugin, which maps each ABI to its Swift SDK triple, SwiftPM scratch and
output directories, Swift runtime directory and NDK `libc++_shared.so`. A plugin
unit test fails when they differ. Adding an ABI means adding it to all three and
qualifying it on a device.

Each ABI other than the default builds with its own `--scratch-path`
(`.build/android-<abi>` in the package). Two triples in one SwiftPM scratch
directory share a build manifest but not a build description: after the first
build of the second triple, every build of the first fails with `No target
named '<product>-<triple>-debug.dylib' in build description`. The plugin's
tests cannot see this, because their `swiftly` is a script. After changing how
the plugin invokes SwiftPM, run a real two-ABI consumer through the sequence
that exposes it: build, edit a Swift source, build again, twice.

`Scripts/verify_native_payload.py` checks what was actually packaged. It reads
the archive and the ELF headers itself, so it needs no NDK; `--readelf` adds a
cross-check against the NDK's `llvm-readelf` (under `linux-x86_64` on Linux):

```bash
./gradlew :swift-tui-host:assembleRelease
python3 Scripts/verify_native_payload.py --abi arm64-v8a --abi x86_64 \
  swift-tui-host/build/outputs/aar/swift-tui-host-release.aar

python3 Scripts/verify_native_payload.py --abi arm64-v8a --abi x86_64 \
  --readelf "$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" \
  path/to/consumer/app/build/outputs/apk/debug/app-debug.apk
```

For an AAR it requires the JNI shim for every named ABI and nothing else (no
Swift runtime, no `libc++`). For an APK it requires the shim, the Swift host,
the Swift runtime and `libc++_shared.so` per ABI, the same library set in every
ABI, every `DT_NEEDED` resolved inside the ABI directory or by the platform, the
right ELF machine for each directory, and no ABI directory that was not named.

`arm64-v8a` has been built, installed and driven on an emulator. `x86_64` has
been built and its payload verified, but not run: an x86_64 system image needs
an x86_64 host with hardware virtualization.

The Kotlin host decodes image opacity from the shared web-surface record and
applies it through the Compose canvas paint. Bitmap cache keys remain based on
image content identity; alpha-only frames replay the cached bitmap.

## Document import tests

`SwiftTUIHostState.importDocument` is tested in three layers. The JVM tests
(`SwiftTUIDocumentImportStoreTest`, `SwiftTUIDocumentImporterTest`,
`SwiftTUIDocumentNamesTest`) run the copy, the size and type policy, cleanup,
and the once-or-nothing delivery contract against scripted document sources and
a temporary directory. `SwiftTUIInputTest` pins the `file://` drop encoding;
swift-tui's `DroppedPathParsingTests` pins the same vector on the parsing side,
so change both together. The `ContentResolver` adapter
(`SwiftTUIContentDocument`) has no JVM test: it only runs on a device, against
a real provider.

## Text input

`SwiftTUIHostView` keeps an invisible `BasicTextField` as the soft keyboard's
editor. `SwiftTUIImeSession` decides what it holds:

- **Mirror.** When the loaded Swift host library advertises the `textInput`
  operation and the frame describes the focused control (an enabled, nonsecure
  `textField` or `textEditor` node with an action target, `editText`, a text
  value and `textSelection`), the editor holds that control's text and
  selection. Each input-method edit sends the whole buffer with its UTF-16
  selection and composing range. A frame whose action-response watermark is
  below the last edit's request ID is older than that edit and is not
  mirrored; otherwise a frame whose text or selection differs from the editor
  replaces it and ends the composition. Hardware keys, and key events a
  keyboard sends, go to the runtime as terminal bytes before the editor sees
  them. A line break typed into a single-line control is sent as Return.
- **Byte path.** Otherwise (an older library, a secure field, a control the
  frame does not describe) the editor is a scratch buffer diffed by
  `SwiftTUIIme.bytesForEdit`, exactly as before the operation existed.

Offsets are UTF-16 code units end to end. The host never counts characters:
the runtime translates code units to its own grapheme offsets.

## Releases

The Android host versions independently; the coordination root owns component
release qualification. Its named C/JNI and wire contracts determine compatibility,
not matching framework version numbers. See [COMPATIBILITY.md](COMPATIBILITY.md).
Publication currently targets the GitHub Pages Maven repository (`swifttui.github.io/swift-tui-android`) as the interim
distribution channel.

The Swift 6.4 Android runtime requires NDK 30's C++ shared runtime. NDK 27
can compile the JNI shim but fails to load `libswiftCore.so` because
`std::__ndk1::__hash_memory` is missing. Keep the plugin's packaged
`libc++_shared.so` and Swift SDK sysroot on the same NDK installation.
