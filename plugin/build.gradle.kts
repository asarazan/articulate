// The Gradle plugin(s) (D1/D10): `net.sarazan.articulate` (source discovery,
// generateAndroidRes/generateXcstrings/generateStrings/verifyStrings, the
// extension) and `net.sarazan.articulate.android` (AGP variant res wiring
// only). Thin — delegates all real logic to `core`.
//
// AGP is `compileOnly` and pinned to the D9 floor (8.5.2) precisely so this
// module cannot accidentally compile against a newer AGP API that a
// floor-Gradle/floor-AGP consumer's runtime wouldn't have — see
// GradleFloorFunctionalTest, which exercises the actual floor via TestKit.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
}

kotlin {
    // Pins the compile JDK independently of whatever JDK launches Gradle —
    // matches AGP 8.x's own JDK 17 requirement (PLAN.md §12).
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    compileOnly(libs.android.gradle.plugin)

    testImplementation(project(":core"))
    testImplementation(gradleTestKit())
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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

tasks.test {
    useJUnitPlatform()
}
