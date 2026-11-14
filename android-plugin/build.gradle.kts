// The `compatibility { }` block on a plugin declaration comes from
// org.gradle.plugin-compatibility, which plugin-publish applies.
import org.gradle.plugin.compatibility.compatibility

plugins {
  `kotlin-dsl`
  `maven-publish`
  id("com.gradle.plugin-publish") version "2.2.1"
}

// `kotlin-dsl` applies `java-gradle-plugin`, which auto-creates the plugin-marker
// publication for the precompiled `sh.swifttui.android` plugin; `maven-publish`
// publishes it and the implementation jar to the GitHub Pages Maven repository
// (configured in the root build), where consumers resolve the plugin today.
//
// `com.gradle.plugin-publish` adds the Gradle Plugin Portal as a second
// destination for the same two publications (`publishPlugins`). Nothing has
// been published there: the Portal needs a maintainer-held API key and an
// approved plugin id. Until then the plugin only contributes the listing
// metadata below.

// Hosts the SwiftTUI Android convention plugin (id: "sh.swifttui.android"),
// which wires the per-app Swift -> .so cross-build + jniLibs merge so consumers
// apply it instead of pasting build logic. Pure Gradle API — no AGP dependency
// (the app keeps its own `android {}` jniLibs source set; the plugin only owns
// the Swift build tasks and the preBuild wiring).

// Plugin Portal identity. The id, the `sh.swifttui:android-plugin` coordinates
// and the version (the root build's, shared with the AAR) are unchanged; these
// fields are what the Portal requires on top and what its listing shows.
gradlePlugin {
  website = "https://swifttui.sh"
  vcsUrl = "https://github.com/SwiftTUI/swift-tui-android"
  plugins {
    // The precompiled script plugin is declared under its id.
    named("sh.swifttui.android") {
      displayName = "SwiftTUI Android host"
      description =
        "Cross-builds a SwiftTUI app's Swift host product with the Swift Android SDK " +
        "and stages it, with the Swift runtime, into an Android app's jniLibs for " +
        "each selected ABI. Pairs with the sh.swifttui:android-host AAR."
      tags = listOf("swift", "swifttui", "android", "jni", "compose", "tui")
      // Held by SwiftTuiAndroidPluginFunctionalTest, which stores and reuses a
      // configuration cache entry for a multi-ABI build.
      compatibility {
        features {
          configurationCache = true
        }
      }
    }
  }
}

// plugin-publish adds a sources and a javadoc jar to the implementation
// publication. The Pages repository receives that same publication, so they
// would become two new files per release there, and the javadoc jar is empty
// (the plugin is a Kotlin script). Leave them out so the Pages layout stays
// exactly jar + POM + module file + marker. plugin-publish itself does not
// require them: `publishPlugins` uploads whatever the publication holds. That
// the Portal accepts a publication without them has not been tried; delete
// this block if it does not, or when a later destination wants them (Maven
// Central requires both).
//
// plugin-publish adds the two variants in its own afterEvaluate, registered
// when the plugins block applied it, so this one runs after it.
afterEvaluate {
  (components["java"] as AdhocComponentWithVariants).run {
    withVariantsFromConfiguration(configurations["sourcesElements"]) { skip() }
    withVariantsFromConfiguration(configurations["javadocElements"]) { skip() }
  }
}

// The published identity is a compatibility contract: consumers name the plugin
// id and the Pages repository serves these exact coordinates. `publishPlugins`
// would check the Portal half of this, but only at publication time; hold all
// of it in `check`, which needs no credentials and no network.
val verifyPluginPublication = tasks.register("verifyPluginPublication") {
  description = "Fails if the plugin's published coordinates, marker or Portal metadata drift."
  group = "verification"

  val implementationPomTask = tasks.named("generatePomFileForPluginMavenPublication")
  val markerPomTask = tasks.named("generatePomFileForSh.swifttui.androidPluginMarkerMavenPublication")
  dependsOn(implementationPomTask, markerPomTask)

  val implementationPom = layout.buildDirectory.file("publications/pluginMaven/pom-default.xml")
  val markerPom = layout.buildDirectory.file("publications/sh.swifttui.androidPluginMarkerMaven/pom-default.xml")
  inputs.files(implementationPom, markerPom)

  // Task-local values: the action must not reach back into the project.
  val releaseVersion = rootProject.version.toString()
  val declaration = gradlePlugin.plugins.getByName("sh.swifttui.android")
  val portalMetadata = mapOf(
    "plugin id" to declaration.id,
    "website" to gradlePlugin.website.orNull,
    "vcsUrl" to gradlePlugin.vcsUrl.orNull,
    "displayName" to declaration.displayName,
    "description" to declaration.description,
    "tags" to declaration.tags.orNull?.joinToString(",")
  )

  doLast {
    fun coordinates(node: org.w3c.dom.Element): String =
      listOf("groupId", "artifactId", "version").joinToString(":") { name ->
        (0 until node.childNodes.length)
          .map { node.childNodes.item(it) }
          .firstOrNull { it.nodeName == name }?.textContent.orEmpty()
      }
    fun parse(file: File): org.w3c.dom.Element =
      javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement

    val failures = mutableListOf<String>()
    fun expect(what: String, expected: String, actual: String?) {
      if (actual != expected) failures += "$what: expected $expected, found $actual"
    }

    val implementation = "sh.swifttui:android-plugin:$releaseVersion"
    expect("implementation coordinates", implementation, coordinates(parse(implementationPom.get().asFile)))

    val marker = parse(markerPom.get().asFile)
    expect(
      "marker coordinates",
      "sh.swifttui.android:sh.swifttui.android.gradle.plugin:$releaseVersion",
      coordinates(marker)
    )
    val dependencies = marker.getElementsByTagName("dependency")
    expect("marker dependency count", "1", dependencies.length.toString())
    if (dependencies.length == 1) {
      expect(
        "marker dependency (the implementation, at the same version)",
        implementation,
        coordinates(dependencies.item(0) as org.w3c.dom.Element)
      )
    }

    expect("plugin id", "sh.swifttui.android", portalMetadata["plugin id"])
    for ((field, value) in portalMetadata) {
      if (value.isNullOrBlank()) failures += "Plugin Portal metadata: $field is not set"
    }

    if (failures.isNotEmpty()) {
      throw GradleException("The plugin publication drifted:\n  " + failures.joinToString("\n  "))
    }
  }
}

tasks.check {
  dependsOn(verifyPluginPublication)
}

dependencies {
  // `java-gradle-plugin` already puts Gradle TestKit and the plugin-under-test
  // classpath on the `test` source set; only the test framework is added here.
  testImplementation("junit:junit:4.13.2")
}

tasks.test {
  // The functional tests run real Gradle builds against a fake Swift toolchain
  // (see SwiftTuiAndroidPluginFunctionalTest), so they need neither Swift nor
  // the NDK. Keep TestKit's daemon and caches inside this build rather than in
  // the per-user temp default, which other checkouts would share.
  systemProperty("swifttui.testKitDir", layout.buildDirectory.dir("test-kit").get().asFile.path)
  // The AAR's shim ABI lists, which a unit test holds equal to the plugin's.
  val hostModuleDir = rootProject.layout.projectDirectory.dir("swift-tui-host")
  systemProperty("swifttui.hostModuleDir", hostModuleDir.asFile.path)
  inputs.file(hostModuleDir.file("build.gradle.kts")).withPathSensitivity(PathSensitivity.RELATIVE)
  inputs.file(hostModuleDir.file("src/main/jni/Application.mk")).withPathSensitivity(PathSensitivity.RELATIVE)
}
