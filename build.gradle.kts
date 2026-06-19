plugins {
  id("com.android.library") version "9.2.1" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// The published AAR and the Gradle plugin share the org's coordinated version.
allprojects {
  group = "sh.swifttui"
  version = "0.0.23"
}

// Both publishable modules serve to the GitHub Pages static Maven repo
// (build/github-pages-repo), which the release step pushes to the gh-pages branch
// (served at https://swifttui.github.io/swift-tui-android). Credential-free — both
// the AAR and the plugin marker resolve from here; no Plugin Portal / Sonatype / GPG.
subprojects {
  plugins.withId("maven-publish") {
    extensions.configure<PublishingExtension> {
      repositories {
        maven {
          name = "githubPages"
          url = uri(rootProject.layout.buildDirectory.dir("github-pages-repo"))
        }
      }
    }
  }
}
