#!/usr/bin/env python3
"""Merge one immutable Android publication into an existing Maven checkout.

Defaults to a preview. Does not commit, push, move tags, or delete history.
"""
import argparse
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

COORDINATES = {
    'sh/swifttui/android-host': 'aar',
    'sh/swifttui/android-plugin': 'jar',
    'sh/swifttui/android/sh.swifttui.android.gradle.plugin': 'pom',
}
HASHES = ('md5', 'sha1', 'sha256', 'sha512')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def version_key(value):
    require(re.fullmatch(r'0\.(0|[1-9]\d*)\.(0|[1-9]\d*)', value or ''), f'unsupported version {value!r}')
    return tuple(map(int, value.split('.')))


def digest(data, algorithm='sha256'):
    return hashlib.new(algorithm, data).hexdigest()


def metadata(path, coordinate):
    document = ET.fromstring(path.read_bytes())
    group, artifact = coordinate.rsplit('/', 1)
    require(document.findtext('groupId') == group.replace('/', '.') and document.findtext('artifactId') == artifact,
            f'{path}: metadata coordinates differ')
    versions = [item.text for item in document.findall('versioning/versions/version')]
    require(versions and len(versions) == len(set(versions)), f'{path}: missing/duplicate versions')
    for value in versions:
        version_key(value)
    timestamp = document.findtext('versioning/lastUpdated')
    require(re.fullmatch(r'\d{14}', timestamp or ''), f'{path}: invalid lastUpdated')
    return set(versions), timestamp


def reject_links(directory):
    require(directory.is_dir() and not directory.is_symlink(), f'missing or symlinked directory: {directory}')
    require(not any(path.is_symlink() for path in directory.rglob('*')), f'{directory}: symbolic links are not publication inputs')


def merge(staging, destination, version, revision):
    version_key(version)
    require(re.fullmatch(r'[a-f0-9]{40}', revision), 'expected full source revision')
    require(staging.resolve() != destination.resolve() and staging.resolve() not in destination.resolve().parents
            and destination.resolve() not in staging.resolve().parents, 'staging and destination must be separate trees')
    reject_links(staging)
    reject_links(destination)
    artifacts = {}
    changes = {}
    metadata_changes = {}
    for coordinate, extension in COORDINATES.items():
        source = staging / coordinate
        target = destination / coordinate
        artifact = coordinate.rsplit('/', 1)[1]
        selected = source / version
        require(selected.is_dir(), f'missing selected artifact directory: {selected}')
        if (target / version).exists():
            require({p.name for p in (target / version).iterdir()} <= {p.name for p in selected.iterdir()},
                    f'{coordinate}: published artifact membership differs')
        for suffix in {'pom', extension}:
            require((selected / f'{artifact}-{version}.{suffix}').is_file(), f'missing {coordinate} {suffix}')
        pom = ET.parse(selected / f'{artifact}-{version}.pom').getroot()
        group = coordinate.rsplit('/', 1)[0].replace('/', '.')
        for key, expected in [('groupId', group), ('artifactId', artifact), ('version', version)]:
            require(pom.findtext('{*}' + key) == expected, f'{coordinate}: wrong POM {key}')
        for path in sorted(selected.iterdir()):
            require(path.is_file(), f'unexpected artifact directory: {path}')
            relative = path.relative_to(staging)
            data = path.read_bytes()
            # Check every emitted checksum before copying anything.
            if path.suffix[1:] in HASHES:
                base = path.with_suffix('')
                require(base.is_file() and data.decode().strip() == digest(base.read_bytes(), path.suffix[1:]), f'{path}: invalid checksum')
            artifacts[str(relative)] = digest(data)
            old = destination / relative
            if old.exists():
                require(old.is_file() and old.read_bytes() == data, f'{relative}: immutable artifact bytes differ; publish a new version')
            else:
                changes[relative] = data
        new_versions, new_time = metadata(source / 'maven-metadata.xml', coordinate)
        require(version in new_versions, f'{coordinate}: selected version missing from staging metadata')
        old_versions, old_time = set(), '00000000000000'
        old_metadata = target / 'maven-metadata.xml'
        if old_metadata.exists():
            old_versions, old_time = metadata(old_metadata, coordinate)
        # Recover older entries even if a prior publisher truncated metadata.
        directories = {path.name for path in target.iterdir() if path.is_dir()} if target.exists() else set()
        for value in directories:
            version_key(value)
        retained = old_versions | directories
        for value in retained:
            if value == version:
                continue  # A matching receipt can resume a partial artifact copy.
            require((target / value / f'{artifact}-{value}.pom').is_file(), f'{coordinate}: historical metadata points to missing {value}')
        versions = sorted(retained | {version}, key=version_key)
        group, name = coordinate.rsplit('/', 1)
        document = ET.Element('metadata')
        ET.SubElement(document, 'groupId').text = group.replace('/', '.')
        ET.SubElement(document, 'artifactId').text = name
        versioning = ET.SubElement(document, 'versioning')
        ET.SubElement(versioning, 'latest').text = versions[-1]
        ET.SubElement(versioning, 'release').text = versions[-1]
        entries = ET.SubElement(versioning, 'versions')
        for value in versions:
            ET.SubElement(entries, 'version').text = value
        ET.SubElement(versioning, 'lastUpdated').text = old_time if version in old_versions else max(old_time, new_time)
        ET.indent(document, space='  ')
        data = ET.tostring(document, encoding='utf-8', xml_declaration=True) + b'\n'
        relative = Path(coordinate) / 'maven-metadata.xml'
        metadata_changes[relative] = data
        for algorithm in HASHES:
            metadata_changes[Path(str(relative) + '.' + algorithm)] = digest(data, algorithm).encode()
    receipt_path = Path('_releases') / f'{version}.json'
    receipt = {'schemaVersion': 1, 'version': version, 'commit': revision, 'artifacts': artifacts}
    receipt_bytes = (json.dumps(receipt, indent=2, sort_keys=True) + '\n').encode()
    previous = destination / receipt_path
    if previous.exists():
        require(previous.read_bytes() == receipt_bytes, 'publication receipt differs; cannot change source or artifact membership')
    else:
        # Pre-receipt versions cannot be relabeled as a new source selection.
        require(not any((destination / p / version).exists() for p in COORDINATES), 'existing version lacks a source receipt; do not republish a historical version')
        changes[receipt_path] = receipt_bytes
    changes.update(metadata_changes)
    return {path: data for path, data in changes.items()
            if not (destination / path).exists() or (destination / path).read_bytes() != data}


def apply(destination, changes):
    # Seal source first so interrupted file copying can resume the same tuple.
    for path, data in sorted(changes.items(), key=lambda item: (item[0].parts[0] != '_releases', str(item[0]))):
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + '.publishing')
        require(not temporary.exists(), f'leftover interrupted write: {temporary}')
        temporary.write_bytes(data)
        temporary.replace(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--staging', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--version', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    try:
        # Public source identity is checked before an authored publication write.
        if args.write:
            root = Path(__file__).resolve().parent.parent
            head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
            status = subprocess.check_output(['git', '-C', str(root), 'status', '--porcelain'], text=True).strip()
            require(head == args.revision and not status, 'publication requires a clean checkout at the selected revision')
            command = ['git', 'ls-remote', 'https://github.com/SwiftTUI/swift-tui-android.git',
                       f'refs/tags/{args.version}', f'refs/tags/{args.version}^{{}}']
            refs = dict(line.split()[::-1] for line in subprocess.check_output(command, text=True).splitlines())
            actual = refs.get(f'refs/tags/{args.version}^{{}}', refs.get(f'refs/tags/{args.version}'))
            require(actual == args.revision, 'published source tag differs from selected revision')
        changes = merge(args.staging, args.destination, args.version, args.revision)
        for path in sorted(changes):
            print(f'{digest(changes[path])}  {path}')
        if args.write:
            apply(args.destination, changes)
        print(f'{"Wrote" if args.write else "Previewed"} {len(changes)} files; historical artifacts retained. No commit or push performed.')
    except (ValueError, OSError, ET.ParseError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'[Maven publication] {error}\n')


if __name__ == '__main__':
    main()
