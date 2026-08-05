package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.requireOrSkipAndroidSdk
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeLocalProperties
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * PLAN.md §4.5/§13: the regression the rest of the functional test suite is
 * structurally blind to.
 *
 * Every other functional test in this module drives its fixture through
 * `GradleRunner.withPluginClasspath()`, which injects **one** classpath for
 * the whole fixture build -- a single plugin classloader, so cross-project
 * class identity always holds and the bug this test exists to catch simply
 * cannot occur. This test instead runs the real, checked-in `sample/`
 * project (`../../../../sample` relative to the `plugin` module) as an
 * actual composite build: its `settings.gradle.kts` does
 * `pluginManagement { includeBuild("..") }` against the repo root, and its
 * two modules -- `:i18n` and `:androidApp` -- each declare
 * `net.sarazan.articulate`/`net.sarazan.articulate.android` in their **own**
 * `plugins {}` block with a version, exactly like Android Studio's default
 * template, with `:androidApp` additionally requesting AGP in its own block.
 * That gives each module a genuinely distinct plugin classloader -- the
 * shape a published artifact would produce, and the one thing TestKit's
 * default injection cannot reach.
 *
 * **Confirmed to reproduce the original defect** (2026-07-31, manual `../gradlew
 * :androidApp:help` against the pre-fix `ArticulateAndroidPlugin`):
 * `org.gradle.api.InvalidUserDataException: The task 'generateAndroidRes'
 * (net.sarazan.articulate.gradle.tasks.GenerateAndroidResTask) is not a
 * subclass of the given type (net.sarazan.articulate.gradle.tasks.GenerateAndroidResTask)`
 * -- the exact failure PLAN.md §13's publishing audit recorded against a
 * *published* artifact. The composite build reproduces it with no publishing
 * step, so it doubles as the regression vehicle and the sample's own
 * end-to-end smoke (§14, D2 tier 3).
 *
 * This test does not use `.withPluginClasspath()` at all -- the plugin
 * classpath comes entirely from `includeBuild(".."), exactly as it would for
 * a real consumer.
 */
class SampleCompositeBuildFunctionalTest {

    /** The checked-in sample, relative to this module's project directory (`plugin/`). */
    private val sampleDir = File("../sample").canonicalFile

    @BeforeEach
    fun setUp() {
        val sdk = requireOrSkipAndroidSdk()
        writeLocalProperties(sampleDir.toPath(), sdk)
        // This test runs the real, checked-in sample/ directory in place --
        // not a fresh @TempDir copy, since includeBuild("..") in its
        // settings.gradle.kts must resolve to the real repo root, which only
        // holds true one level below it. Clearing prior build state before
        // every run keeps outcomes deterministic (SUCCESS, never a stale
        // UP-TO-DATE from a previous run in the same session or a prior
        // manual `cd sample && ./gradlew ...`), without touching anything
        // checked in -- these are exactly the directories already
        // `.gitignore`d.
        File(sampleDir, "i18n/build").deleteRecursively()
        File(sampleDir, "androidApp/build").deleteRecursively()
        File(sampleDir, "build").deleteRecursively()
        File(sampleDir, ".gradle").deleteRecursively()
    }

    @Test
    fun `sample composite build configures the app module without a cross-classloader task lookup failure`() {
        val result = GradleRunner.create()
            .withProjectDir(sampleDir)
            .withArguments(":androidApp:help", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:help")!!.outcome)
    }

    @Test
    fun `sample composite build actually compiles the app module, resolving R string from the i18n module`() {
        val result = GradleRunner.create()
            .withProjectDir(sampleDir)
            .withArguments(":androidApp:compileDebugJavaWithJavac", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:compileDebugJavaWithJavac")!!.outcome)
        // PLAN.md §4.5b: GenerateAndroidResTask is deleted -- validateStrings is the gate.
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:validateStrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:resolveArticulateAndroidRes")!!.outcome)
    }

    @Test
    fun `sample composite build configures cleanly under isolated projects`() {
        // PLAN.md §13's other release-blocker, closed by the same redesign:
        // the pre-fix cross-project task lookup was rejected outright under
        // isolated projects ("Cannot access project ':i18n' from project
        // ':app'"). This is the one automated check of that claim against
        // the real, multi-classloader composite build -- everything else
        // verifying isolated-projects compatibility so far was a manual,
        // throwaway probe (see the milestone report), not a test that runs
        // in CI. `-Dorg.gradle.unsafe.isolated-projects=true` is incubating,
        // so this only asserts configuration succeeds, not any Gradle
        // console banner text that could change across versions.
        val result = GradleRunner.create()
            .withProjectDir(sampleDir)
            .withArguments(":androidApp:help", "-Dorg.gradle.unsafe.isolated-projects=true", "--stacktrace")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:help")!!.outcome)
        assertTrue(
            !result.output.contains("Cannot access project") && !result.output.contains("CircularReferenceException"),
            "expected no isolated-projects violation in the output:\n${result.output}",
        )
    }
}
