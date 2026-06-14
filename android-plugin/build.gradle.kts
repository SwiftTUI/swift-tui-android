plugins {
  `kotlin-dsl`
  `maven-publish`
}

// `kotlin-dsl` applies `java-gradle-plugin`, which auto-creates the plugin-marker
// publication for the precompiled `sh.swifttui.android` plugin; `maven-publish`
// lets `publishToMavenLocal` emit it for local verification. The Gradle Plugin
// Portal publish + listing metadata (com.gradle.plugin-publish) is added in B2b.

// Hosts the SwiftTUI Android convention plugin (id: "sh.swifttui.android"),
// which wires the per-app Swift -> .so cross-build + jniLibs merge so consumers
// apply it instead of pasting build logic. Pure Gradle API — no AGP dependency
// (the app keeps its own `android {}` jniLibs source set; the plugin only owns
// the Swift build tasks and the preBuild wiring).
