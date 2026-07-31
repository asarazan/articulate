// Pure Kotlin. No Gradle API on this classpath by design (D1) — the canonical
// serializer, parser, converter and locale mapper are all testable at plain
// unit-test speed, and stay reusable outside Gradle.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

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
