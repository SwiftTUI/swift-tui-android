plugins {
  id("com.android.library") version "9.2.1" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}

// The published AAR and the Gradle plugin are versioned together (P4).
allprojects {
  group = "sh.swifttui"
  version = "0.1.0"
}
