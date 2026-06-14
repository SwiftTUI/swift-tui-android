pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

rootProject.name = "swift-tui-android"

// The published AAR (sh.swifttui:android-host) and the Gradle convention plugin
// (sh.swifttui.android). The plugin is a sibling subproject, not an included
// build — this repo publishes it, the library does not consume it.
include(":swift-tui-host")
include(":android-plugin")
