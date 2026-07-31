package net.sarazan.articulate.gradle

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * PLAN.md §4.5: `net.sarazan.articulate.android`'s variant res wiring.
 *
 * Disabled wholesale -- this machine has no Android SDK (no `ANDROID_HOME`,
 * no `~/Library/Android/sdk`, no `local.properties`), and applying
 * `com.android.application` in a TestKit fixture fails during AGP's own
 * project configuration without one (it needs to locate `platforms/`,
 * `build-tools/`, etc., regardless of whether the build ever reaches a real
 * compile). No placeholder here asserts anything that could pass vacuously;
 * per this project's own rule ("a test which cannot fail is worse than no
 * test"), each case below is `@Disabled` rather than faked, following
 * `core/src/test/kotlin/.../serialize/RoundTripTest.kt`'s pattern for the
 * still-pending Xcode fixture.
 *
 * [ArticulateAndroidPlugin] itself does compile cleanly against the real AGP
 * 8.5.2 jar (`plugin/build.gradle.kts`'s `compileOnly`), which is a real,
 * automated check on the API surface used
 * (`ApplicationAndroidComponentsExtension`, `variant.sources.res`,
 * `addGeneratedSourceDirectory`) -- but compiling against AGP's API classes
 * and actually running AGP against a project are different guarantees, and
 * only the first is available here. Untested behavior, concretely:
 *
 *  - That `variant.sources.res?.addGeneratedSourceDirectory(...)` actually
 *    registers `build/generated/i18n/res` as a resource source for a real
 *    variant, and that `R.string.hello` resolves from it in generated code.
 *  - That the wiring applies per-variant correctly (debug/release, flavors).
 *  - That [ArticulateAndroidExtension.i18nProject]'s cross-project task
 *    lookup (`project.project(path).tasks.named("generateAndroidRes", ...)`)
 *    resolves correctly in a real multi-module Android + `:i18n` build, and
 *    that its `GradleException` fires with a clear message when the
 *    referenced project doesn't exist or hasn't applied `net.sarazan.articulate`.
 *  - That nothing here ever writes into a checked-in `res` source set (the
 *    hard requirement from §4.5) under a real AGP resource-merge run.
 */
@Disabled("no Android SDK on this machine (no ANDROID_HOME/~/Library/Android/sdk/local.properties) -- com.android.application cannot configure without one; see the milestone 4/5 report for exactly what remains unverified")
class AndroidWiringFunctionalTest {

    @Test
    fun `generated Android res is registered as a variant source directory and R string resolves it`() {
        TODO("Requires a real Android SDK to apply com.android.application in a TestKit fixture.")
    }

    @Test
    fun `i18nProject cross-project task lookup resolves generateAndroidRes from a sibling module`() {
        TODO("Requires a real Android SDK -- same blocker as above.")
    }
}
