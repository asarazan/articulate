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
}
