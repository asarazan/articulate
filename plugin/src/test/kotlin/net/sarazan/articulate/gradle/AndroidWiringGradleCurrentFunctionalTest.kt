package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.COMPILE_SDK
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
 * PLAN.md §E2/D9, REVISED 2026-08-03: this is the matrix's second cell, and
 * its axis is **Gradle version, not AGP**. Both cells now run the same AGP
 * (9.1.0, [FunctionalTestSupport.AGP_VERSION]) -- the difference is that
 * [AndroidWiringFunctionalTest] pins `.withGradleVersion(ANDROID_GRADLE_FLOOR_VERSION)`
 * (9.3.1, the Android floor) while this class runs under this repo's own
 * wrapper Gradle (9.5.0) with no version pin at all. Before the revision,
 * this class ran a *different* AGP (9.1) against the *floor* AGP (8.5.2) in
 * the other cell, which needed its own explicit, second TestKit plugin
 * classpath configuration and metadata task (see `plugin/build.gradle.kts`'s
 * history) to avoid loading two conflicting AGP copies. With a single AGP
 * version, that machinery has no remaining job and was deleted -- this
 * class now uses the same default,
 * classpath-resource-based `GradleRunner.withPluginClasspath()` every other
 * functional test in this source set relies on.
 *
 * `compileSdk`/`targetSdk` are [COMPILE_SDK] (37) -- the same constant
 * [AndroidWiringFunctionalTest] uses, since both cells run the same AGP and
 * therefore support the same compileSdk ceiling.
 *
 * Deliberately a lighter test set than [AndroidWiringFunctionalTest]: the two
 * `i18nProject` misconfiguration tests there (missing project; project
 * without `net.sarazan.articulate` applied) exercise plumbing that never
 * touches AGP at all, so they'd prove nothing new here. What genuinely needs
 * re-verifying on the wrapper Gradle is (a) the actual variant-sources wiring
 * API still resolves and works, and (b) configuration-cache compatibility
 * holds.
 */
class AndroidWiringGradleCurrentFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        val sdk = requireOrSkipAndroidSdk()
        writeLocalProperties(projectDir, sdk)
    }

    private fun gradleCurrentRunner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            // Deliberately no .withGradleVersion(...): this cell's whole
            // point is to run under this repo's own wrapper Gradle (9.5.0),
            // not a pinned version -- TestKit's default (no explicit
            // version) is exactly "run under the Gradle that's building this
            // project".
            .withArguments(*args)
            .forwardOutput()

    /** Same shape as [AndroidWiringFunctionalTest.writeTwoModuleFixture], sharing the same [COMPILE_SDK]. */
    private fun writeTwoModuleFixture(): File {
        writeAndroidSettings(projectDir)
        val i18nDir = File(projectDir.toFile(), "i18n").apply { mkdirs() }
        writeBaseBuildFile(i18nDir.toPath())
        writeValidStringsFixture(i18nDir.toPath())
        writeAndroidAppModule(projectDir, compileSdk = COMPILE_SDK)
        return i18nDir
    }

    @Test
    fun `wrapper Gradle -- generated Android res is registered as a variant source directory and R string resolves it`() {
        writeTwoModuleFixture()

        val result = gradleCurrentRunner(":app:compileDebugJavaWithJavac", ":app:compileReleaseJavaWithJavac").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDebugJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileReleaseJavaWithJavac")!!.outcome)
        // PLAN.md §4.5b: GenerateAndroidResTask is deleted -- validateStrings is the gate.
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:validateStrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveArticulateAndroidRes")!!.outcome)
        // Marker.java (written by writeAndroidAppModule) references
        // R.string.hello -- javac succeeding is proof this cell's resource
        // merge and R-class generation actually picked up the wired
        // directory, exactly as for the floor cell.

        val checkedInRes = File(projectDir.toFile(), "app/src/main/res")
        assertFalse(checkedInRes.exists(), "app/src/main/res must never exist under the wrapper Gradle cell either")

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
            "expected the wrapper Gradle cell to relocate the generated res into :app's build dir exactly like the " +
                "floor cell now does (PLAN.md §4.5/§13 redesign closed the prior divergence): $relocated",
        )

        // PLAN.md §4.5b: GenerateAndroidResTask is deleted -- :i18n no longer
        // materializes any Android res copy of its own on either cell (see
        // the identical assertion in AndroidWiringFunctionalTest).
        assertFalse(
            File(projectDir.toFile(), "i18n/build/generated/i18n/res").exists(),
            "GenerateAndroidResTask is deleted -- :i18n must not generate its own Android res copy any more",
        )
    }

    @Test
    fun `wrapper Gradle -- articulateAndroidResIncoming resolves validateStrings's gate from a sibling module`() {
        writeTwoModuleFixture()

        val result = gradleCurrentRunner(":app:resolveArticulateAndroidRes").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveArticulateAndroidRes")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:validateStrings")!!.outcome)
    }

    @Test
    fun `wrapper Gradle -- configuration cache is reused for the Android app path`() {
        writeTwoModuleFixture()

        val first = gradleCurrentRunner(":app:compileDebugJavaWithJavac", "--configuration-cache").build()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "expected the first run to store a configuration-cache entry under the wrapper Gradle cell:\n${first.output}",
        )

        val second = gradleCurrentRunner(":app:compileDebugJavaWithJavac", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Configuration cache entry reused."),
            "expected the second run to reuse the configuration-cache entry under the wrapper Gradle cell too:\n${second.output}",
        )
    }
}
