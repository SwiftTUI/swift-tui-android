#!/usr/bin/env python3
"""Offline rehearsal of the native payload check against synthetic archives."""
import struct
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

import verify_native_payload as payload

ARM64, X86_64 = 183, 62
RUNTIME = ['libswiftCore.so', 'libFoundation.so']


def elf(machine, needed=(), elf_class=2, alignment=0x4000):
    """A minimal shared object: one LOAD, one DYNAMIC, a string table."""
    strings = b'\0' + b''.join(name.encode() + b'\0' for name in needed)
    table = 64 + 2 * 56
    dynamic = table + len(strings) + (-len(strings) % 8)
    offsets, position = [], 1
    for name in needed:
        offsets.append(position)
        position += len(name) + 1
    entries = [(1, offset) for offset in offsets] + [(5, table), (10, len(strings)), (0, 0)]
    size = dynamic + 16 * len(entries)
    header = b'\x7fELF' + bytes([elf_class, 1, 1]) + bytes(9)
    header += struct.pack('<HHIQQQIHHHHHH', 3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 64, 0, 0)
    load = struct.pack('<IIQQQQQQ', 1, 5, 0, 0, 0, size, size, alignment)
    dynamic_segment = struct.pack('<IIQQQQQQ', 2, 6, dynamic, dynamic, dynamic, 16 * len(entries), 16 * len(entries), 8)
    body = header + load + dynamic_segment + strings
    body += bytes(dynamic - len(body))
    return body + b''.join(struct.pack('<qQ', tag, value) for tag, value in entries)


def abi_libraries(machine):
    """What the plugin and the AAR put in one lib/<abi>/ directory."""
    return {
        'libswift_tui_jni.so': elf(machine, ['libdl.so', 'liblog.so', 'libc.so']),
        'libswift_tui_app_host.so': elf(machine, ['libswiftCore.so', 'libFoundation.so', 'libc.so']),
        'libswiftCore.so': elf(machine, ['libc++_shared.so', 'libm.so', 'libc.so']),
        'libFoundation.so': elf(machine, ['libswiftCore.so', 'libc.so']),
        'libc++_shared.so': elf(machine, ['libc.so']),
    }


class NativePayloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def archive(self, name, entries):
        path = self.directory / name
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')  # Duplicate entries are a case under test.
            with zipfile.ZipFile(path, 'w') as bundle:
                for entry, data in entries:
                    bundle.writestr(entry, data)
        return path

    def apk(self, abis=None, edit=None, extra=()):
        tree = {abi: abi_libraries(machine)
                for abi, machine in (abis or {'arm64-v8a': ARM64, 'x86_64': X86_64}).items()}
        if edit:
            edit(tree)
        entries = [(f'lib/{abi}/{name}', data) for abi, libraries in tree.items() for name, data in libraries.items()]
        return self.archive('app.apk', [('classes.dex', b'dex'), *entries, *extra])

    def aar(self, shims):
        entries = [(f'jni/{abi}/{name}', data) for abi, libraries in shims.items() for name, data in libraries.items()]
        return self.archive('host.aar', [('classes.jar', b'jar'), *entries])

    def failures(self, archive, *abis, **options):
        return payload.verify(archive, list(abis), **options)[1]

    def assertFails(self, expected, archive, *abis, **options):
        failures = self.failures(archive, *abis, **options)
        self.assertTrue(any(expected in failure for failure in failures), failures)

    def test_complete_two_abi_apk_and_aar_pass_and_report_every_library(self):
        rows, failures = payload.verify(self.apk(), ['arm64-v8a', 'x86_64'])
        self.assertEqual(failures, [])
        self.assertEqual({(row[0], row[2]) for row in rows}, {('arm64-v8a', ARM64), ('x86_64', X86_64)})
        self.assertEqual(len(rows), 10)
        self.assertEqual(next(row[6] for row in rows if row[:2] == ('x86_64', 'libswiftCore.so')),
                         ['libc++_shared.so', 'libm.so', 'libc.so'])
        shim = {'libswift_tui_jni.so': elf(ARM64, ['libdl.so', 'liblog.so'])}
        shim64 = {'libswift_tui_jni.so': elf(X86_64, ['libdl.so', 'liblog.so'])}
        self.assertEqual(self.failures(self.aar({'arm64-v8a': shim, 'x86_64': shim64}), 'arm64-v8a', 'x86_64'), [])

    def test_arm64_only_default_passes_and_an_unselected_abi_directory_fails(self):
        arm64_only = self.apk({'arm64-v8a': ARM64})
        self.assertEqual(self.failures(arm64_only, 'arm64-v8a'), [])
        self.assertFails('lib/x86_64/libswift_tui_jni.so: missing', arm64_only, 'arm64-v8a', 'x86_64')
        # A two-ABI archive checked as arm64-only advertises an ABI nobody selected.
        self.assertFails('advertises x86_64, which was not selected', self.apk(), 'arm64-v8a')
        graphics = [('lib/armeabi-v7a/libandroidx.graphics.path.so', elf(40, elf_class=1))]
        with_other = self.apk({'arm64-v8a': ARM64}, extra=graphics)
        self.assertFails('advertises armeabi-v7a', with_other, 'arm64-v8a')
        self.assertEqual(self.failures(with_other, 'arm64-v8a', allow_other_abis=True), [])

    def test_a_library_built_for_another_machine_fails_in_either_direction(self):
        def swap(tree):
            tree['x86_64']['libswiftCore.so'] = tree['arm64-v8a']['libswiftCore.so']
        self.assertFails('lib/x86_64/libswiftCore.so: ELF class 2 machine 183, but x86_64 is class 2 machine 62',
                         self.apk(edit=swap), 'arm64-v8a', 'x86_64')

        def narrow(tree):
            tree['arm64-v8a']['libc++_shared.so'] = elf(40, elf_class=1)
        self.assertFails('lib/arm64-v8a/libc++_shared.so: ELF class 1 machine 40', self.apk(edit=narrow),
                         'arm64-v8a', 'x86_64')

        def corrupt(tree):
            tree['x86_64']['libswift_tui_jni.so'] = b'not a library'
        self.assertFails('lib/x86_64/libswift_tui_jni.so: not an ELF file', self.apk(edit=corrupt),
                         'arm64-v8a', 'x86_64')

    def test_missing_unresolved_and_unequal_libraries_fail(self):
        for library in ['libswift_tui_jni.so', 'libswift_tui_app_host.so', 'libswiftCore.so', 'libc++_shared.so']:
            self.assertFails(f'lib/x86_64/{library}: missing',
                             self.apk(edit=lambda tree: tree['x86_64'].pop(library)), 'arm64-v8a', 'x86_64')
        missing_dependency = self.apk(edit=lambda tree: tree['x86_64'].pop('libFoundation.so'))
        self.assertFails('lib/x86_64/libswift_tui_app_host.so: needs libFoundation.so', missing_dependency,
                         'arm64-v8a', 'x86_64')
        self.assertFails('lib/x86_64/libFoundation.so: missing, but lib/arm64-v8a/ has it', missing_dependency,
                         'arm64-v8a', 'x86_64')

        def static_runtime(tree):
            tree['arm64-v8a']['libswift_tui_app_host.so'] = elf(ARM64, ['libc.so'])
        self.assertFails('does not link libswiftCore.so dynamically', self.apk(edit=static_runtime),
                         'arm64-v8a', 'x86_64')

    def test_a_second_copy_of_a_library_fails_wherever_it_hides(self):
        stray = [('assets/libswiftCore.so', elf(ARM64))]
        self.assertFails('assets/libswiftCore.so: native library outside lib/<abi>/', self.apk(extra=stray),
                         'arm64-v8a', 'x86_64')
        twice = [('lib/x86_64/libswiftCore.so', elf(X86_64, ['libc.so']))]
        self.assertFails('lib/x86_64/libswiftCore.so: packaged 2 times', self.apk(extra=twice),
                         'arm64-v8a', 'x86_64')

    def test_aar_must_ship_the_shim_for_every_abi_and_no_runtime(self):
        shim = {'libswift_tui_jni.so': elf(ARM64, ['libdl.so', 'liblog.so'])}
        self.assertFails('jni/x86_64/libswift_tui_jni.so: missing', self.aar({'arm64-v8a': shim}),
                         'arm64-v8a', 'x86_64')
        bundled = dict(shim, **{'libswiftCore.so': elf(ARM64)})
        self.assertFails('jni/arm64-v8a/libswiftCore.so: the AAR must ship only libswift_tui_jni.so',
                         self.aar({'arm64-v8a': bundled}), 'arm64-v8a')
        needs_stl = {'libswift_tui_jni.so': elf(ARM64, ['libc++_shared.so'])}
        self.assertFails('jni/arm64-v8a/libswift_tui_jni.so: needs libc++_shared.so',
                         self.aar({'arm64-v8a': needs_stl}), 'arm64-v8a')

    def test_alignment_floor_and_argument_errors(self):
        def small_pages(tree):
            tree['x86_64']['libswift_tui_jni.so'] = elf(X86_64, ['libc.so'], alignment=0x1000)
        archive = self.apk(edit=small_pages)
        self.assertEqual(self.failures(archive, 'arm64-v8a', 'x86_64'), [])
        self.assertFails('lib/x86_64/libswift_tui_jni.so: LOAD alignment 4096 is below 16384', archive,
                         'arm64-v8a', 'x86_64', min_load_alignment=16384)
        with self.assertRaisesRegex(ValueError, 'armeabi-v7a is not an ABI SwiftTUI packages'):
            payload.verify(archive, ['armeabi-v7a'])
        with self.assertRaisesRegex(ValueError, 'name at least one --abi'):
            payload.verify(archive, [])
        with self.assertRaisesRegex(ValueError, 'pass --kind'):
            payload.verify(self.archive('payload.zip', []), ['arm64-v8a'])


if __name__ == '__main__':
    unittest.main()
