// The Gradle plugin(s) (D1/D10): `net.sarazan.articulate` (source discovery,
// generateAndroidRes/generateXcstrings/generateStrings/verifyStrings, the
// extension) and `net.sarazan.articulate.android` (AGP variant res wiring
// only). Thin — delegates all real logic to `core`.
//
// AGP is `compileOnly` and pinned to the D9 floor (8.5.2) precisely so this
// module cannot accidentally compile against a newer AGP API that a
// floor-Gradle/floor-AGP consumer's runtime wouldn't have — see
// GradleFloorFunctionalTest, which exercises the actual floor via TestKit.
import org.gradle.plugin.devel.tasks.PluginUnderTestMetadata

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin {
    // Pins the compile JDK independently of whatever JDK launches Gradle —
    // matches AGP 8.x's own JDK 17 requirement (PLAN.md §12).
    jvmToolchain(17)
}

// AGP added here, not as a `dependencies {}` scope, precisely so it is never
// part of `compileClasspath`/`runtimeClasspath`/anything that flows into the
// published POM of either plugin ID -- D10's whole point is that a
// non-Android consumer of `net.sarazan.articulate` never pulls AGP onto its
// classpath, and the two plugin IDs share this one jar. This configuration
// exists purely to feed `pluginUnderTestMetadata` below, for TestKit.
val agpTestKitClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.android.gradle.plugin)

    testImplementation(project(":core"))
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    agpTestKitClasspath(libs.android.gradle.plugin)
}

gradlePlugin {
    plugins {
        create("articulate") {
            id = "net.sarazan.articulate"
            implementationClass = "net.sarazan.articulate.gradle.ArticulatePlugin"
            displayName = "Articulate"
            description = "Generates an Xcode String Catalog from Android strings.xml, and verifies it hasn't drifted."
        }
        create("articulateAndroid") {
            id = "net.sarazan.articulate.android"
            implementationClass = "net.sarazan.articulate.gradle.ArticulateAndroidPlugin"
            displayName = "Articulate (Android variant wiring)"
            description = "Wires Articulate's generated Android resources into AGP variant sources."
        }
    }
    // Explicit rather than relying on java-gradle-plugin's default source-set
    // detection: wires this module's own compiled classes + runtime
    // classpath into `plugin-under-test-metadata.properties`, which
    // GradleRunner.withPluginClasspath() reads to make `plugins { id(...) }`
    // resolvable inside a TestKit fixture project without publishing anything.
    testSourceSets(sourceSets["test"])
}

// `PluginUnderTestMetadata.pluginClasspath` defaults to `sourceSets.main.runtimeClasspath`
// alone, which never includes `compileOnly(libs.android.gradle.plugin)` --
// `compileOnly` is deliberately excluded from every runtime classpath by
// Gradle. Without this, ArticulateAndroidPlugin's compiled reference to
// AGP's `ApplicationAndroidComponentsExtension` throws `NoClassDefFoundError`
// inside any TestKit fixture built with `withPluginClasspath()`, since AGP's
// classes are simply absent from the injected classpath -- not merely in a
// different classloader. `.from()` on a `ConfigurableFileCollection` appends
// rather than replaces, so this adds `agpTestKitClasspath` (AGP + its
// transitive deps) alongside the existing default, without touching what
// gets published (see `agpTestKitClasspath`'s own comment, above).
tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agpTestKitClasspath)
}

tasks.test {
    useJUnitPlatform()
}
