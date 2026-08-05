// Pure Kotlin. No Gradle API on this classpath by design (D1) — the canonical
// serializer, parser, converter and locale mapper are all testable at plain
// unit-test speed, and stay reusable outside Gradle.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Publishing coordinates. 0.1.0 deliberately, not 1.0.0: `markupPolicy` accepts
// STRIP and VERBATIM in the DSL but only ERROR is implemented (D4), the M6
// Swift key-parity lint is not built, and generated common-layer tokens are
// scoped but not built (PLAN.md §14 amendment). 0.x is the honest signal.
// Set per-module rather than via allprojects{} -- the root script forbids
// cross-project configuration precisely because it breaks project isolation.
group = "net.sarazan.articulate"
version = "0.1.0"

kotlin {
    // Pins the compile JDK independently of whatever JDK launches Gradle.
    jvmToolchain(17)
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    // The golden corpus (PLAN.md §2.1) is read straight off the filesystem by
    // CorpusTest's @TestFactory rather than from the test *resources* tree, so
    // Gradle cannot infer it as an input. Without this declaration, editing a
    // corpus fixture leaves `:core:test` UP-TO-DATE and the change is never
    // executed — verified: appending an unmatchable required substring to an
    // expected-error.txt still produced BUILD SUCCESSFUL. "Adding a case = adding
    // files, no code" only holds if adding files actually re-runs the tests.
    inputs.dir(layout.projectDirectory.dir("src/test/corpus"))
        .withPropertyName("goldenCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
