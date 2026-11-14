#!/usr/bin/env python3
"""Check the native payload of a host AAR or a consumer APK, ABI by ABI.

For every ABI named with --abi this proves, from the archive alone:

  AAR  jni/<abi>/ holds the JNI shim and nothing else. The AAR must not carry
       the Swift runtime or libc++: the Gradle plugin stages one copy of each
       per ABI in the consumer, and a second copy in the AAR would collide.
  APK  lib/<abi>/ holds the shim, the Swift host library, the Swift runtime
       and libc++; every DT_NEEDED of every library there resolves inside the
       same directory or to an Android system library; all selected ABIs carry
       the same library set; no library sits outside lib/<abi>/.

  Both: each library is a 64-bit little-endian ELF whose machine is the one its
       ABI directory claims, and the archive advertises no ABI beyond the ones
       named (an extra lib/<abi>/ installs on that device with no Swift host).

Reads ELF headers itself, so it needs no NDK. Pass --readelf to also ask
llvm-readelf for each library's machine and require the two to agree.
"""
import argparse
import collections
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

SHIM = 'libswift_tui_jni.so'
SWIFT_CORE = 'libswiftCore.so'
CXX_SHARED = 'libc++_shared.so'

# ABI -> (ELF class, e_machine, llvm-readelf's name for that machine).
MACHINES = {
    'arm64-v8a': (2, 183, 'AArch64'),
    'x86_64': (2, 62, 'Advanced Micro Devices X86-64'),
    'armeabi-v7a': (1, 40, 'ARM'),
    'x86': (1, 3, 'Intel 80386'),
    'riscv64': (2, 243, 'RISC-V'),
}
# The ABIs SwiftTUI packages; the rest are known only so they can be named.
SUPPORTED = ('arm64-v8a', 'x86_64')

# Libraries the platform provides (the NDK's stable native API surface). A
# DT_NEEDED outside this set must be packaged beside the library that needs it.
SYSTEM_LIBRARIES = frozenset('lib%s.so' % name for name in (
    'EGL', 'GLESv1_CM', 'GLESv2', 'GLESv3', 'OpenMAXAL', 'OpenSLES', 'aaudio',
    'amidi', 'android', 'binder_ndk', 'c', 'camera2ndk', 'dl', 'icu',
    'jnigraphics', 'log', 'm', 'mediandk', 'nativehelper', 'nativewindow',
    'neuralnetworks', 'stdc++', 'sync', 'vulkan', 'z'))

PT_LOAD, PT_DYNAMIC = 1, 2
DT_NULL, DT_NEEDED, DT_STRTAB = 0, 1, 5


class Elf:
    """The few ELF facts this check needs: class, machine, alignment, NEEDED."""

    def __init__(self, data):
        if len(data) < 20 or data[:4] != b'\x7fELF':
            raise ValueError('not an ELF file')
        self.elf_class, encoding = data[4], data[5]
        if encoding != 1:
            raise ValueError('not little-endian')
        self.machine = struct.unpack_from('<H', data, 18)[0]
        self.needed, self.load_alignment = [], None
        if self.elf_class != 2:
            return  # A 32-bit library is reported by class; its layout differs.
        phoff, = struct.unpack_from('<Q', data, 32)
        phentsize, phnum = struct.unpack_from('<HH', data, 54)
        loads, dynamic = [], None
        for index in range(phnum):
            kind, _, offset, vaddr, _, filesz, _, align = struct.unpack_from(
                '<IIQQQQQQ', data, phoff + index * phentsize)
            if kind == PT_LOAD:
                loads.append((vaddr, offset, filesz))
                self.load_alignment = align if self.load_alignment is None else min(self.load_alignment, align)
            elif kind == PT_DYNAMIC:
                dynamic = (offset, filesz)
        if dynamic is None:
            return
        entries = [struct.unpack_from('<qQ', data, dynamic[0] + position)
                   for position in range(0, dynamic[1] - 15, 16)]
        if (DT_NULL, 0) in entries:
            entries = entries[:entries.index((DT_NULL, 0))]
        table = next((value for tag, value in entries if tag == DT_STRTAB), None)
        if table is None:
            return
        base = next((offset + table - vaddr for vaddr, offset, filesz in loads
                     if vaddr <= table < vaddr + filesz), None)
        if base is None:
            raise ValueError('dynamic string table lies outside every LOAD segment')
        for tag, value in entries:
            if tag == DT_NEEDED:
                end = data.index(b'\0', base + value)
                self.needed.append(data[base + value:end].decode())


def readelf_machine(readelf, data):
    with tempfile.NamedTemporaryFile(suffix='.so') as file:
        file.write(data)
        file.flush()
        output = subprocess.run([readelf, '-h', file.name], check=True, capture_output=True, text=True).stdout
    for line in output.splitlines():
        if line.strip().startswith('Machine:'):
            return line.split(':', 1)[1].strip()
    raise ValueError('llvm-readelf printed no Machine line')


def verify(archive, abis, host_library='swift_tui_app_host', kind=None, readelf=None,
           allow_other_abis=False, min_load_alignment=0):
    """Returns (rows, failures); an empty failure list means the payload is sound."""
    archive = Path(archive)
    kind = kind or archive.suffix.lstrip('.').lower()
    if kind not in ('aar', 'apk'):
        raise ValueError(f'cannot tell whether {archive.name} is an AAR or an APK; pass --kind')
    abis = list(dict.fromkeys(abis))
    for abi in abis:
        if abi not in SUPPORTED:
            raise ValueError(f'{abi} is not an ABI SwiftTUI packages ({", ".join(SUPPORTED)})')
    if not abis:
        raise ValueError('name at least one --abi')
    root = 'jni' if kind == 'aar' else 'lib'
    host = f'lib{host_library}.so'
    failures, rows, libraries = [], [], {}

    with zipfile.ZipFile(archive) as bundle:
        names = [name for name in bundle.namelist() if not name.endswith('/')]
        for name, count in sorted(collections.Counter(names).items()):
            if count > 1:
                failures.append(f'{name}: packaged {count} times')
        for name in sorted(set(names)):
            path = PurePosixPath(name)
            if path.suffix != '.so':
                continue
            if len(path.parts) != 3 or path.parts[0] != root:
                failures.append(f'{name}: native library outside {root}/<abi>/')
                continue
            libraries.setdefault(path.parts[1], {})[path.name] = bundle.read(name)

    for abi in sorted(set(libraries) - set(abis)):
        if abi in SUPPORTED or not allow_other_abis:
            failures.append(
                f'{root}/{abi}/: the archive advertises {abi}, which was not selected '
                f'({", ".join(sorted(libraries[abi]))})')

    if kind == 'aar':
        required = [SHIM]
    else:
        required = [SHIM, host, SWIFT_CORE, CXX_SHARED]

    for abi in abis:
        present = libraries.get(abi, {})
        for library in required:
            if library not in present:
                failures.append(f'{root}/{abi}/{library}: missing')
        if kind == 'aar':
            for library in sorted(set(present) - {SHIM}):
                failures.append(
                    f'{root}/{abi}/{library}: the AAR must ship only {SHIM}; '
                    'the plugin stages the Swift runtime and libc++ in the consumer')
        elf_class, machine, readelf_name = MACHINES[abi]
        for library, data in sorted(present.items()):
            where = f'{root}/{abi}/{library}'
            try:
                elf = Elf(data)
            except (ValueError, struct.error, IndexError) as error:
                failures.append(f'{where}: {error}')
                continue
            if (elf.elf_class, elf.machine) != (elf_class, machine):
                failures.append(
                    f'{where}: ELF class {elf.elf_class} machine {elf.machine}, '
                    f'but {abi} is class {elf_class} machine {machine}')
            reported = ''
            if readelf:
                reported = readelf_machine(readelf, data)
                if reported != readelf_name:
                    failures.append(f'{where}: llvm-readelf reports {reported!r}, expected {readelf_name!r}')
            if min_load_alignment and (elf.load_alignment or 0) < min_load_alignment:
                failures.append(f'{where}: LOAD alignment {elf.load_alignment} is below {min_load_alignment}')
            for needed in elf.needed:
                if needed not in present and needed not in SYSTEM_LIBRARIES:
                    failures.append(f'{where}: needs {needed}, which is not in {root}/{abi}/')
            rows.append((abi, library, elf.machine, reported, elf.load_alignment, len(data), elf.needed))
        if kind == 'apk' and host in present and SWIFT_CORE in present:
            try:
                if SWIFT_CORE not in Elf(present[host]).needed:
                    failures.append(
                        f'{root}/{abi}/{host}: does not link {SWIFT_CORE} dynamically; '
                        'a statically linked runtime would be a second copy')
            except (ValueError, struct.error, IndexError):
                pass  # Already reported above.

    if kind == 'apk':
        reference = set(libraries.get(abis[0], {}))
        for abi in abis[1:]:
            other = set(libraries.get(abi, {}))
            for library in sorted(reference - other):
                failures.append(f'{root}/{abi}/{library}: missing, but {root}/{abis[0]}/ has it')
            for library in sorted(other - reference):
                failures.append(f'{root}/{abis[0]}/{library}: missing, but {root}/{abi}/ has it')

    return rows, failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('archive', type=Path, help='an .aar or .apk')
    parser.add_argument('--abi', action='append', default=[], required=True,
                        help='an ABI the archive must carry completely; repeat for each one')
    parser.add_argument('--kind', choices=('aar', 'apk'), help='default: from the file extension')
    parser.add_argument('--host-library', default='swift_tui_app_host',
                        help='the plugin\'s hostLibraryName (default: %(default)s)')
    parser.add_argument('--readelf', help='llvm-readelf from the NDK, to cross-check each machine type')
    parser.add_argument('--allow-other-abis', action='store_true',
                        help='tolerate ABI directories SwiftTUI does not package (third-party libraries)')
    parser.add_argument('--min-load-alignment', type=int, default=0,
                        help='fail when a LOAD segment is aligned below this (16384 for 16 KB pages)')
    args = parser.parse_args()
    try:
        rows, failures = verify(args.archive, args.abi, args.host_library, args.kind, args.readelf,
                                args.allow_other_abis, args.min_load_alignment)
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(2, f'[native payload] {error}\n')
    for abi, library, machine, reported, alignment, size, needed in rows:
        print(f'{abi:<10} {library:<36} machine={machine:<3} {reported:<30} '
              f'align={alignment:<6} {size:>9} bytes  needs: {" ".join(needed) or "-"}')
    for failure in failures:
        print(f'FAIL {failure}', file=sys.stderr)
    counts = ', '.join(f'{abi}: {sum(1 for row in rows if row[0] == abi)} libraries' for abi in dict.fromkeys(args.abi))
    print(f'{args.archive.name}: {"FAILED" if failures else "ok"} ({counts})')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
