#!/usr/bin/env python3
"""Compile the published C signatures and exercise the production JNI loader table."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JNI = ROOT / "swift-tui-host/src/main/jni"
TEST = ROOT / "swift-tui-host/src/test/native"

def run(*args):
    subprocess.run(args, check=True)

with tempfile.TemporaryDirectory(prefix="swifttui-abi-") as directory:
    output = Path(directory)
    runner = str(output / "caller")
    common = ["-Wall", "-Wextra", "-Werror", "-I", str(JNI)]
    run(os.environ.get("CXX", "c++"), "-std=c++17", *common,
        str(TEST / "abi_loader_test.cpp"), "-o", runner,
        *(["-ldl"] if sys.platform != "darwin" else []))
    # Minor 1 operations: bit 5 is swift_tui_android_copy_open_link, bit 6 is
    # swift_tui_android_take_clipboard_text.
    cases = [
        ("candidate", [], "ok", "no-take"),
        ("legacy", ["-DLEGACY"], "ok", "no-take"),
        ("missing-start", ["-DOMIT_START"], "android.abi.missingRequired: swift_tui_android_start", "no-take"),
        ("missing-factory", ["-DOMIT_FACTORY"], "android.abi.missingRequired: swift_tui_android_create_host", "no-take"),
        ("wrong-major", ["-DABI_MAJOR=2"], "android.abi.incompatibleMajor", "no-take"),
        ("wrong-width", ["-DCOUNT_BITS=64"], "android.abi.argumentWidth", "no-take"),
        ("short-info", ["-DINFO_BYTES=16"], "android.abi.shortInfo", "no-take"),
        ("missing-advertised", ["-DOPERATIONS=1"], "android.abi.missingAdvertised", "no-take"),
        # Open link (operation bit 5): resolved only when advertised and
        # exported, and by a pre-query library that exports it.
        ("open-link", ["-DOPERATIONS=32", "-DWITH_OPEN_LINK"], "ok+open-link", "no-take"),
        ("open-link-legacy", ["-DLEGACY", "-DWITH_OPEN_LINK"], "ok+open-link", "no-take"),
        ("open-link-unadvertised", ["-DWITH_OPEN_LINK"], "ok", "no-take"),
        ("open-link-missing", ["-DOPERATIONS=32"],
         "android.abi.missingAdvertised: swift_tui_android_copy_open_link", "no-take"),
        # Take clipboard (operation bit 6), by the same rule.
        ("take", ["-DOPERATIONS=64", "-DWITH_TAKE"], "ok", "take"),
        ("take-minor-0", ["-DABI_MINOR=0", "-DWITH_TAKE"], "ok", "no-take"),
        ("take-unadvertised", ["-DWITH_TAKE"], "ok", "no-take"),
        ("take-legacy", ["-DLEGACY", "-DWITH_TAKE"], "ok", "take"),
        ("take-missing", ["-DOPERATIONS=64"],
         "android.abi.missingAdvertised: swift_tui_android_take_clipboard_text", "no-take"),
        ("later-minor-unknown-bit", ["-DABI_MINOR=9", "-DOPERATIONS=4160", "-DWITH_TAKE"], "ok", "take"),
        # The two operations are independent: each bit resolves its own symbol
        # and leaves the other unresolved, even when the library exports both.
        ("open-link-and-take", ["-DOPERATIONS=96", "-DWITH_OPEN_LINK", "-DWITH_TAKE"], "ok+open-link", "take"),
        ("open-link-bit-only", ["-DOPERATIONS=32", "-DWITH_OPEN_LINK", "-DWITH_TAKE"], "ok+open-link", "no-take"),
        ("take-bit-only", ["-DOPERATIONS=64", "-DWITH_OPEN_LINK", "-DWITH_TAKE"], "ok", "take"),
        ("open-link-and-take-legacy", ["-DLEGACY", "-DWITH_OPEN_LINK", "-DWITH_TAKE"], "ok+open-link", "take"),
    ]
    for name, defines, expected, take in cases:
        library = str(output / (name + ".so"))
        run(os.environ.get("CC", "cc"), "-std=c11", *common, "-shared", "-fPIC",
            *defines, str(TEST / "abi_fixture.c"), "-o", library)
        run(runner, library, expected, take)
