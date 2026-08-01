package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.AGP_91_VERSION
import net.sarazan.articulate.gradle.FunctionalTestSupport.COMPILE_SDK_AGP_91
import net.sarazan.articulate.gradle.FunctionalTestSupport.agp91PluginClasspath
import net.sarazan.articulate.gradle.FunctionalTestSupport.requireOrSkipAndroidSdk
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeAndroidAppModule
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeAndroidSettings
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeBaseBuildFile
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeLocalProperties
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeValidStringsFixture
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Task 3 (PLAN.md §E2/D9): the AGP 9.1 upper matrix cell -- until this class,
 * "AGP 9.1" was a claim in a table with nothing behind it, the exact kind of
 * unverified assertion this project refuses everywhere else. This is
 * [AndroidWiringFunctionalTest]'s AGP 8.5.2/Gradle-8.7-floor coverage,
 * re-run against AGP 9.1.0 with no Gradle version pin (so it runs under this
 * repo's own wrapper, 9.5.0 -- AGP 9.1 requires Gradle >= 9.3.1, which 9.5.0
 * satisfies; it is *not* compatible with [FunctionalTestSupport.GRADLE_FLOOR_VERSION],
 * so unlike the floor cell this one cannot reuse that pin).
 *
 * The classpath comes from [agp91PluginClasspath] rather than the default,
 * no-arg `GradleRunner.withPluginClasspath()` -- see that function's KDoc and
 * `plugin/build.gradle.kts` for why: the default classpath resource is
 * permanently pinned to AGP 8.5.2 for every other functional test in this
 * source set, so this class builds its own explicit classpath instead of
 * disturbing that.
 *
 * `compileSdk`/`targetSdk` are [COMPILE_SDK_AGP_91] (37), the max AGP 9.1
 * supports, paired with build-tools 36.0.0 -- both installed locally
 * (`~/Library/Android/sdk/platforms/android-37.0`,
 * `~/Library/Android/sdk/build-tools/36.0.0`) alongside the floor's
 * platform 34 / build-tools 34.0.0, verified 2026-07-31.
 *
 * Deliberately a lighter test set than [AndroidWiringFunctionalTest]: the two
 * `i18nProject` misconfiguration tests there (missing project; project
 * without `net.sarazan.articulate` applied) exercise plumbing that never
 * touches AGP at all, so they'd prove nothing new here. What genuinely needs
 * re-verifying per AGP version is (a) the actual variant-sources wiring API
 * still resolves and works, and (b) configuration-cache compatibility holds.
 */
class AndroidWiringAgp91FunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        val sdk = requireOrSkipAndroidSdk()
        writeLocalProperties(projectDir, sdk)
    }

    private fun agp91Runner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath(agp91PluginClasspath())
            // Deliberately no .withGradleVersion(...): AGP 9.1 requires
            // Gradle >= 9.3.1, which this repo's own wrapper (9.5.0) already
            // satisfies, and TestKit's default (no explicit version) is
            // exactly "run under the Gradle that's building this project".
            .withArguments(*args)
            .forwardOutput()

    /** Same shape as [AndroidWiringFunctionalTest.writeTwoModuleFixture], but `compileSdk`/`targetSdk` pinned to [COMPILE_SDK_AGP_91]. */
    private fun writeTwoModuleFixture(): File {
        writeAndroidSettings(projectDir)
        val i18nDir = File(projectDir.toFile(), "i18n").apply { mkdirs() }
        writeBaseBuildFile(i18nDir.toPath())
        writeValidStringsFixture(i18nDir.toPath())
        writeAndroidAppModule(projectDir, compileSdk = COMPILE_SDK_AGP_91)
        return i18nDir
    }

    @Test
    fun `AGP 9 1 -- generated Android res is registered as a variant source directory and R string resolves it`() {
        writeTwoModuleFixture()

        val result = agp91Runner(":app:compileDebugJavaWithJavac", ":app:compileReleaseJavaWithJavac").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDebugJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileReleaseJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:generateAndroidRes")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveArticulateAndroidRes")!!.outcome)
        // Marker.java (written by writeAndroidAppModule) references
        // R.string.hello -- javac succeeding is proof AGP 9.1's resource
        // merge and R-class generation actually picked up the wired
        // directory, exactly as for the floor cell.

        val checkedInRes = File(projectDir.toFile(), "app/src/main/res")
        assertFalse(checkedInRes.exists(), "app/src/main/res must never exist under AGP 9.1 either")

        // Before PLAN.md §4.5/§13's redesign, this cell genuinely diverged
        // from the floor's: AGP 9.1 left GenerateAndroidResTask's output in
        // place at the PRODUCING :i18n project's own build dir instead of
        // relocating it into :app, unlike AGP 8.5.2. That divergence is gone
        // now, not by accident: the task AGP wires via
        // addGeneratedSourceDirectory is net.sarazan.articulate.android's own
        // ResolveArticulateAndroidResTask, registered IN the app module --
        // so "relocated into :app's build tree" and "left where the wired
        // task wrote it" are the same location on both AGP cells. Verified
        // 2026-08-01 against a real AGP 9.1 build (dumping the full
        // app/build + i18n/build trees): both this file and the floor
        // cell's equivalent assertion in AndroidWiringFunctionalTest now
        // expect the identical relative path.
        val relocated = File(projectDir.toFile(), "app/build/generated/res/resolveArticulateAndroidRes/values/strings.xml")
        assertTrue(
            relocated.isFile,
            "expected AGP $AGP_91_VERSION to relocate the generated res into :app's build dir exactly like the " +
                "AGP 8.5.2 floor cell now does (PLAN.md §4.5/§13 redesign closed the prior divergence): $relocated",
        )

        // :i18n's own generateAndroidRes still materializes at its own
        // convention location too -- it is the artifact the consumable
        // configuration publishes, not the task AGP wires directly any
        // more, so this is expected on both cells (see the identical
        // assertion in AndroidWiringFunctionalTest).
        val i18nOwnCopy = File(projectDir.toFile(), "i18n/build/generated/i18n/res/values/strings.xml")
        assertTrue(i18nOwnCopy.isFile, "expected :i18n's own generateAndroidRes to still materialize at its own convention location: $i18nOwnCopy")
    }

    @Test
    fun `AGP 9 1 -- articulateAndroidResIncoming resolves generateAndroidRes's output from a sibling module`() {
        writeTwoModuleFixture()

        val result = agp91Runner(":app:resolveArticulateAndroidRes").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveArticulateAndroidRes")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:generateAndroidRes")!!.outcome)
    }

    @Test
    fun `AGP 9 1 -- configuration cache is reused for the Android app path`() {
        writeTwoModuleFixture()

        val first = agp91Runner(":app:compileDebugJavaWithJavac", "--configuration-cache").build()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "expected the first run to store a configuration-cache entry under AGP $AGP_91_VERSION:\n${first.output}",
        )

        val second = agp91Runner(":app:compileDebugJavaWithJavac", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Configuration cache entry reused."),
            "expected the second run to reuse the configuration-cache entry under AGP $AGP_91_VERSION too:\n${second.output}",
        )
    }
}
