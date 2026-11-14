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
    # swift_tui_android_take_clipboard_text, bit 7 is swift_tui_android_text_input.
    # The last element of a case names the ones the loader must resolve; it must
    # leave every other one unresolved.
    every = ["-DWITH_OPEN_LINK", "-DWITH_TAKE", "-DWITH_TEXT_INPUT"]
    cases = [
        ("candidate", [], "ok", ""),
        ("legacy", ["-DLEGACY"], "ok", ""),
        ("missing-start", ["-DOMIT_START"], "android.abi.missingRequired: swift_tui_android_start", ""),
        ("missing-factory", ["-DOMIT_FACTORY"], "android.abi.missingRequired: swift_tui_android_create_host", ""),
        ("wrong-major", ["-DABI_MAJOR=2"], "android.abi.incompatibleMajor", ""),
        ("wrong-width", ["-DCOUNT_BITS=64"], "android.abi.argumentWidth", ""),
        ("short-info", ["-DINFO_BYTES=16"], "android.abi.shortInfo", ""),
        ("missing-advertised", ["-DOPERATIONS=1"], "android.abi.missingAdvertised", ""),
        # Open link (operation bit 5): resolved only when advertised and
        # exported, and by a pre-query library that exports it.
        ("open-link", ["-DOPERATIONS=32", "-DWITH_OPEN_LINK"], "ok", "open-link"),
        ("open-link-legacy", ["-DLEGACY", "-DWITH_OPEN_LINK"], "ok", "open-link"),
        ("open-link-unadvertised", ["-DWITH_OPEN_LINK"], "ok", ""),
        ("open-link-missing", ["-DOPERATIONS=32"],
         "android.abi.missingAdvertised: swift_tui_android_copy_open_link", ""),
        # Take clipboard (operation bit 6), by the same rule.
        ("take", ["-DOPERATIONS=64", "-DWITH_TAKE"], "ok", "take"),
        ("take-minor-0", ["-DABI_MINOR=0", "-DWITH_TAKE"], "ok", ""),
        ("take-unadvertised", ["-DWITH_TAKE"], "ok", ""),
        ("take-legacy", ["-DLEGACY", "-DWITH_TAKE"], "ok", "take"),
        ("take-missing", ["-DOPERATIONS=64"],
         "android.abi.missingAdvertised: swift_tui_android_take_clipboard_text", ""),
        ("later-minor-unknown-bit", ["-DABI_MINOR=9", "-DOPERATIONS=4160", "-DWITH_TAKE"], "ok", "take"),
        # Text input (operation bit 7), by the same rule.
        ("text-input", ["-DOPERATIONS=128", "-DWITH_TEXT_INPUT"], "ok", "text-input"),
        ("text-input-legacy", ["-DLEGACY", "-DWITH_TEXT_INPUT"], "ok", "text-input"),
        ("text-input-unadvertised", ["-DWITH_TEXT_INPUT"], "ok", ""),
        ("text-input-missing", ["-DOPERATIONS=128"],
         "android.abi.missingAdvertised: swift_tui_android_text_input", ""),
        # The operations are independent: each bit resolves its own symbol and
        # leaves the others unresolved, even when the library exports them all.
        ("all-minor-1", ["-DOPERATIONS=224", *every], "ok", "open-link,take,text-input"),
        ("open-link-bit-only", ["-DOPERATIONS=32", *every], "ok", "open-link"),
        ("take-bit-only", ["-DOPERATIONS=64", *every], "ok", "take"),
        ("text-input-bit-only", ["-DOPERATIONS=128", *every], "ok", "text-input"),
        ("all-minor-1-legacy", ["-DLEGACY", *every], "ok", "open-link,take,text-input"),
    ]
    for name, defines, expected, resolved in cases:
        library = str(output / (name + ".so"))
        run(os.environ.get("CC", "cc"), "-std=c11", *common, "-shared", "-fPIC",
            *defines, str(TEST / "abi_fixture.c"), "-o", library)
        run(runner, library, expected, resolved or "none")
