# Cell clipping qualification

The manual Android instrumentation fixture measures the real renderer's
`drawCells` method on a 160×60 grid of `W` glyphs. It compares the production
save/clip/restore path with a test-only Canvas that suppresses those calls. The
deliberately narrow cells expose the pixels clipping protects. It also checks
that replacing one cell incrementally produces the same bitmap as a fresh paint.
Production clipping remains enabled.

With an emulator or device attached:

```sh
./gradlew :swift-tui-host:assembleDebugAndroidTest
adb install -r swift-tui-host/build/outputs/apk/androidTest/debug/swift-tui-host-debug-androidTest.apk
adb shell am instrument -w sh.swifttui.android.host.test/sh.swifttui.android.host.ClipQualificationRunner
```

The fixture uses five warmup paints and reports the median of twenty measured
paints. Output includes `CLIP-QUALIFICATION`, elapsed milliseconds, the 9,600
clip calls, differing pixels in the unclipped counterfactual, and incremental
equivalence. A thrown assertion is returned through instrumentation's error
result. This device lane is separate from the default JVM/native gate.
