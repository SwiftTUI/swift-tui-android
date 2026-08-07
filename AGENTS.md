# AGENTS.md

Guidance for agentic assistants working in this repository. Keep this file
concise. `README.md` is the consumer-facing story; `docs/` holds internal
notes.

## What this repo is

The Gradle/Maven distribution half of SwiftTUI's Android host: the
`sh.swifttui:android-host` AAR (Compose host + JNI shim) and the
`sh.swifttui.android` Gradle plugin that cross-builds a consumer's Swift host.
The Swift half is the `SwiftTUIAndroidHost` SwiftPM product in
`SwiftTUI/swift-tui`.

## Build & test commands

```bash
./gradlew :swift-tui-host:testDebugUnitTest   # JVM unit tests (NDK-free)
./gradlew :swift-tui-host:assembleRelease     # the AAR
./gradlew publishToMavenLocal                 # AAR + plugin into ~/.m2
Scripts/native_gate.sh                    # the repo gate CI runs
```

## Rules

- Consumers depend on the tagged `SwiftTUIAndroidHost` SwiftPM product over
  HTTPS. Never introduce a path dependency; pre-tag integration happens in
  the SwiftTUI org coordination root.
- Planning and proposal documents live in the SwiftTUI org coordination root,
  not here. `docs/` describes `HEAD` only.

## Conventions

- Agent guidance uses `AGENTS.md` as the real file. `CLAUDE.md` is a symlink
  to it.
