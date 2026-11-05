#!/usr/bin/env python3
"""Offline publication rehearsal: preserve history and refuse mutable releases."""
import tempfile
import unittest
from pathlib import Path

import merge_maven_release as release


class MavenRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        root = Path(self.temporary.name)
        self.staging, self.destination = root / 'staging', root / 'pages'
        self.destination.mkdir()
        self.revision = 'a' * 40
        self.seed(self.staging, '0.21.0', b'new')
        self.seed(self.destination, '0.20.1', b'old')
        (self.destination / 'CNAME').write_text('example.invalid\n')

    def seed(self, root, version, content):
        for coordinate, extension in release.COORDINATES.items():
            path = root / coordinate
            (path / version).mkdir(parents=True, exist_ok=True)
            group, artifact = coordinate.rsplit('/', 1)
            for suffix in {'pom', extension}:
                file = path / version / f'{artifact}-{version}.{suffix}'
                if suffix == 'pom':
                    file.write_text(f'<project><groupId>{group.replace("/", ".")}</groupId><artifactId>{artifact}</artifactId><version>{version}</version></project>')
                else:
                    file.write_bytes(content + suffix.encode())
                for algorithm in release.HASHES:
                    Path(str(file) + '.' + algorithm).write_text(release.digest(file.read_bytes(), algorithm))
            (path / 'maven-metadata.xml').write_text(
                f'<metadata><groupId>{group.replace("/", ".")}</groupId><artifactId>{artifact}</artifactId>'
                f'<versioning><latest>{version}</latest><release>{version}</release><versions><version>{version}</version>'
                '</versions><lastUpdated>20261105190000</lastUpdated></versioning></metadata>')

    def snapshot(self):
        return {str(p.relative_to(self.destination)): p.read_bytes()
                for p in self.destination.rglob('*') if p.is_file()}

    def prepare(self, revision=None):
        return release.merge(self.staging, self.destination, '0.21.0', revision or self.revision)

    def test_merges_fresh_staging_with_all_historical_versions_and_checksums(self):
        original = self.snapshot()
        changes = self.prepare()
        self.assertEqual(self.snapshot(), original, 'preview changed destination')
        release.apply(self.destination, changes)
        for path, data in original.items():
            if 'maven-metadata' not in path:
                self.assertEqual((self.destination / path).read_bytes(), data)
        for coordinate in release.COORDINATES:
            metadata = self.destination / coordinate / 'maven-metadata.xml'
            versions, _ = release.metadata(metadata, coordinate)
            self.assertEqual(versions, {'0.20.1', '0.21.0'})
            for algorithm in release.HASHES:
                self.assertEqual(Path(str(metadata) + '.' + algorithm).read_text(), release.digest(metadata.read_bytes(), algorithm))
        self.assertEqual(self.prepare(), {}, 'matching retry should have no changes')

    def test_partial_copy_resumes_when_source_receipt_and_existing_bytes_match(self):
        changes = self.prepare()
        first = {path: data for path, data in changes.items() if path.parts[0] == '_releases'}
        payload = next(path for path in changes if path.suffix == '.aar')
        first[payload] = changes[payload]
        release.apply(self.destination, first)
        remaining = self.prepare()
        self.assertNotIn(payload, remaining)
        release.apply(self.destination, remaining)
        self.assertEqual(self.prepare(), {})

    def test_different_bytes_or_source_fail_without_modifying_any_files(self):
        release.apply(self.destination, self.prepare())
        before = self.snapshot()
        with self.assertRaisesRegex(ValueError, 'receipt differs'):
            self.prepare('b' * 40)
        file = next(self.staging.rglob('*.aar'))
        file.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'immutable artifact bytes differ'):
            self.prepare()
        self.assertEqual(self.snapshot(), before)

    def test_checksum_missing_pair_and_historical_republication_fail(self):
        checksum = next(self.staging.rglob('*.sha256'))
        checksum.write_text('0' * 64)
        with self.assertRaisesRegex(ValueError, 'invalid checksum'):
            self.prepare()
        checksum.write_text(release.digest(checksum.with_suffix('').read_bytes()))
        marker = self.staging / 'sh/swifttui/android/sh.swifttui.android.gradle.plugin/0.21.0/sh.swifttui.android.gradle.plugin-0.21.0.pom'
        marker.unlink()
        with self.assertRaisesRegex(ValueError, 'missing .* pom'):
            self.prepare()
        self.seed(self.staging, '0.20.1', b'old')
        with self.assertRaisesRegex(ValueError, 'lacks a source receipt'):
            release.merge(self.staging, self.destination, '0.20.1', self.revision)

    def test_truncated_old_metadata_recovers_existing_version_directories(self):
        self.seed(self.destination, '0.19.5', b'older')  # Simulate prior metadata truncation.
        release.apply(self.destination, self.prepare())
        for coordinate in release.COORDINATES:
            versions, _ = release.metadata(self.destination / coordinate / 'maven-metadata.xml', coordinate)
            self.assertEqual(versions, {'0.19.5', '0.20.1', '0.21.0'})

    def test_patch_to_older_line_retains_newer_latest_selection(self):
        self.seed(self.destination, '0.22.1', b'newer')
        release.apply(self.destination, self.prepare())
        for coordinate in release.COORDINATES:
            document = release.ET.parse(self.destination / coordinate / 'maven-metadata.xml')
            self.assertEqual(document.findtext('versioning/latest'), '0.22.1')
            self.assertEqual(document.findtext('versioning/release'), '0.22.1')


if __name__ == '__main__':
    unittest.main()
