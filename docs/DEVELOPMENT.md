# Development

## Toolchains

The Android SDK plus NDK `27.3.13750724` (`minSdk 28`) build the JNI shim;
Swift 6.3.x with the Swift Android SDK cross-compiles the host `.so`. The
repository does not vendor these toolchains.

## Build & test

```bash
./gradlew :swift-tui-host:testDebugUnitTest   # JVM unit tests (NDK-free)
./gradlew :swift-tui-host:assembleRelease     # the AAR
./gradlew publishToMavenLocal                 # AAR + plugin into ~/.m2
Scripts/native_gate.sh                    # the repo gate CI runs
```

The full host build (Swift cross-compile plus emulator) lives in the
`swift-tui-examples/AndroidGallery` example, which consumes these artifacts.

The Kotlin host decodes image opacity from the shared web-surface record and
applies it through the Compose canvas paint. Bitmap cache keys remain based on
image content identity; alpha-only frames replay the cached bitmap.

## Releases

Versions are lockstep with the SwiftTUI org; the coordination root owns the
release sequence. Publication currently targets the GitHub Pages Maven
repository (`swifttui.github.io/swift-tui-android`) as the interim
distribution channel.
