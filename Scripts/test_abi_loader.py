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
    cases = [
        ("candidate", [], "ok"),
        ("legacy", ["-DLEGACY"], "ok"),
        ("missing-start", ["-DOMIT_START"], "android.abi.missingRequired: swift_tui_android_start"),
        ("missing-factory", ["-DOMIT_FACTORY"], "android.abi.missingRequired: swift_tui_android_create_host"),
        ("wrong-major", ["-DABI_MAJOR=2"], "android.abi.incompatibleMajor"),
        ("wrong-width", ["-DCOUNT_BITS=64"], "android.abi.argumentWidth"),
        ("short-info", ["-DINFO_BYTES=16"], "android.abi.shortInfo"),
        ("missing-advertised", ["-DOPERATIONS=1"], "android.abi.missingAdvertised"),
        # Open link (operation bit 5): resolved only when advertised and
        # exported, and by a pre-query library that exports it.
        ("open-link", ["-DOPERATIONS=32", "-DWITH_OPEN_LINK"], "ok+open-link"),
        ("open-link-legacy", ["-DLEGACY", "-DWITH_OPEN_LINK"], "ok+open-link"),
        ("open-link-unadvertised", ["-DWITH_OPEN_LINK"], "ok"),
        ("open-link-missing", ["-DOPERATIONS=32"],
         "android.abi.missingAdvertised: swift_tui_android_copy_open_link"),
    ]
    for name, defines, expected in cases:
        library = str(output / (name + ".so"))
        run(os.environ.get("CC", "cc"), "-std=c11", *common, "-shared", "-fPIC",
            *defines, str(TEST / "abi_fixture.c"), "-o", library)
        run(runner, library, expected)
