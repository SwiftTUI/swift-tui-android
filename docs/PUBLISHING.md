# Publishing Android artifacts

The host AAR, Gradle plugin, and plugin marker share the version in
`build.gradle.kts`. Build and test them from a clean source selection:

```sh
Scripts/native_gate.sh
./gradlew publishAllPublicationsToGithubPagesRepository
python3 Scripts/verify_native_payload.py --abi arm64-v8a --abi x86_64 \
  build/github-pages-repo/sh/swifttui/android-host/$version/android-host-$version.aar
```

The AAR build compiles the JNI shim only when NDK `30.0.16248370` is installed;
without it Gradle still produces an AAR, with no native library in it. The
payload check is what catches that, or a shim missing for one ABI, before the
artifact becomes immutable.

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

## Gradle Plugin Portal

The plugin build applies `com.gradle.plugin-publish` and carries the listing
metadata the Portal requires (website, VCS URL, display name, description,
tags). Nothing has been published there: `sh.swifttui.android` resolves from the
Pages repository only, and consumer instructions say so.

The Portal publication is the same one Pages serves — plugin id
`sh.swifttui.android`, implementation `sh.swifttui:android-plugin`, the version
in `build.gradle.kts` — so a Portal release is the Pages release, not a second
artifact. `:android-plugin:check` runs `verifyPluginPublication`, which fails if
those coordinates, the marker's single dependency on the implementation, or any
Portal field drifts. The sources and javadoc jars plugin-publish would add are
left out of the publication so the Pages layout stays jar, POM, module file and
marker.

`publishPlugins` needs the maintainer's Portal key and secret
(`gradle.publish.key`/`gradle.publish.secret`, or `GRADLE_PUBLISH_KEY`/
`GRADLE_PUBLISH_SECRET`). `publishPlugins --validate-only` runs the plugin's
local checks first and then asks the Portal to validate, which needs the same
key: without one it passes the local checks and stops at
`Missing publishing keys`. A first publication under a new plugin id also waits
for the Portal's manual approval of that id.
