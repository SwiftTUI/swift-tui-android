# Publishing Android artifacts

The host AAR, Gradle plugin, and plugin marker share the version in
`build.gradle.kts`. Build and test them from a clean source selection:

```sh
Scripts/native_gate.sh
./gradlew publishAllPublicationsToGithubPagesRepository
```

Gradle stages Maven artifacts under `build/github-pages-repo`. A fresh staging
directory is supported; it need not contain historical releases. After the
source tag is public, use a checkout of the current `gh-pages` branch as the
destination for `Scripts/merge_maven_release.py`:

```sh
python3 Scripts/merge_maven_release.py --staging build/github-pages-repo \
  --destination /path/to/gh-pages --version "$version" --revision "$revision"
```

The default is a preview. Add `--write` to apply the validated merge, then review,
commit, and push the destination checkout. The source checkout must be clean at
`$revision`, and the public `$version` tag must resolve to that same commit.
The script does not create tags, commits, or pushes.

Existing artifact bytes are immutable. The merger checks every staged checksum,
POM coordinate, and required member, compares any existing version files, and
retains older versions and unrelated Pages files. It merges Maven metadata using
the published history and regenerates metadata checksums. A source/digest receipt
under `_releases/` allows an interrupted copy to resume with the same inputs.
Changed source, bytes, or membership require a new version. Historical versions
without receipts cannot be republished or relabeled by this tool.

Verify the public Pages URLs and fresh Gradle installation after pushing. A
successful local merge alone does not establish public availability. The offline
history-preservation and retry rehearsals run in `Scripts/native_gate.sh`.
