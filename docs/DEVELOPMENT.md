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
tools/bazel/native_gate.sh                    # the repo gate CI runs
```

The full host build — Swift cross-compile plus emulator — lives in the
`swift-tui-examples/AndroidGallery` example, which consumes these artifacts.

## Releases

Versions are lockstep with the SwiftTUI org; the coordination root owns the
release sequence. Publication currently targets the GitHub Pages Maven
repository (`swifttui.github.io/swift-tui-android`) as the interim
distribution channel.
