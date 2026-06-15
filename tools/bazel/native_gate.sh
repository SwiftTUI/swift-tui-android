#!/usr/bin/env bash
set -euo pipefail

# swift-tui-android native gate: the host library's JVM unit tests + plugin
# validation. NDK-free and no Swift toolchain (the Swift cross-build + emulator
# smoke remain a manual / fast-follow lane), so this needs only a JDK and the
# Android SDK (compileSdk).

script_source="${BASH_SOURCE[0]}"
if command -v realpath >/dev/null 2>&1; then
  script_path="$(realpath "$script_source")"
else
  script_path="$(python3 -c 'import os, sys; print(os.path.realpath(sys.argv[1]))' "$script_source")"
fi
repo_root="$(git -C "$(dirname "$script_path")" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -z "$repo_root" ]]; then
  repo_root="$(cd "$(dirname "$script_path")/../.." && pwd)"
fi
cd "$repo_root"

# Honor the environment, else fall back to common macOS locations.
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "${HOME}/Library/Java/JavaVirtualMachines"/*/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  export ANDROID_HOME="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
fi

if [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
  printf 'swift-tui-android native gate: no JDK found (set JAVA_HOME)\n' >&2
  exit 1
fi
if [[ ! -d "${ANDROID_HOME}" ]]; then
  printf 'swift-tui-android native gate: Android SDK not found at %s (set ANDROID_HOME)\n' "$ANDROID_HOME" >&2
  exit 1
fi

exec ./gradlew --no-daemon --console=plain \
  :swift-tui-host:testDebugUnitTest \
  :android-plugin:check
