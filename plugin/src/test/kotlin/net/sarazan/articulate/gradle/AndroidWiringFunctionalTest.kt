package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.GRADLE_FLOOR_VERSION
import net.sarazan.articulate.gradle.FunctionalTestSupport.findAndroidSdk
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * PLAN.md §4.5: `net.sarazan.articulate.android`'s variant res wiring,
 * exercised against a real Android SDK (`~/Library/Android/sdk`;
 * `android-34`/`android-37.0` platforms, build-tools `36.0.0`).
 *
 * Two environment problems had to be solved, neither by relying on ambient
 * shell state:
 *
 *  - `ANDROID_HOME` is not set in the non-interactive shells this suite runs
 *    under, and even where it is, TestKit runs each fixture as a nested
 *    Gradle build in a fresh temp directory that has no `local.properties`
 *    of its own. [FunctionalTestSupport.findAndroidSdk] resolves the SDK
 *    path once (`ANDROID_HOME`, then `ANDROID_SDK_ROOT`, then this machine's
 *    default install location) and [FunctionalTestSupport.writeLocalProperties]
 *    writes it into every fixture explicitly, making these tests
 *    self-contained and portable to CI rather than tied to one machine's
 *    shell config. If no SDK is found anywhere, [setUp] skips loudly via
 *    `Assumptions.assumeTrue` with a message naming exactly what was
 *    checked -- never a silent pass.
 *  - AGP 8.5.2 (D9's floor) is not verified against this repo's *building*
 *    Gradle (9.5.0) -- AGP's own compatibility table pairs 8.5.x with Gradle
 *    8.7-8.9, and `compileSdk 34` is the ceiling AGP 8.5 supports (not "tested
 *    up to"; going higher either fails outright or passes against a
 *    configuration no floor consumer can run). Every fixture here therefore
 *    pins `.withGradleVersion(GRADLE_FLOOR_VERSION)`, exactly like
 *    [GradleFloorFunctionalTest] already does for the non-Android plugin --
 *    this suite is deliberately a floor-only exercise, not a "whatever's on
 *    this machine" one.
 *
 * What's now genuinely verified (was previously only compile-checked against
 * the AGP jar, never run):
 *  - `variant.sources.res?.addGeneratedSourceDirectory(...)` actually
 *    registers the generated dir as a resource source for real debug *and*
 *    release variants -- proven by compiling a Java source that references
 *    `R.string.hello`, which only resolves if AGP's resource merge and
 *    R-class generation actually picked up the wired directory (a stronger
 *    proof than asserting a task merely ran), plus a direct scan of the
 *    merged-resources output under `app/build` for the string.
 *  - [ArticulateAndroidExtension.i18nProject]'s cross-project task lookup
 *    resolves `generateAndroidRes` from a real sibling `:i18n` module in a
 *    real multi-module build, and its two `GradleException` paths (missing
 *    project; project that hasn't applied `net.sarazan.articulate`) fire
 *    with the messages [ArticulateAndroidPlugin] declares.
 *  - Nothing ever writes into a checked-in `res` source set -- `app/src/main/res`
 *    never exists, and the strings source tree is untouched after the build.
 */
class AndroidWiringFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        val sdk = findAndroidSdk()
        assumeTrue(
            sdk != null,
            "No Android SDK found (checked ANDROID_HOME, ANDROID_SDK_ROOT, and " +
                "~/Library/Android/sdk) -- skipping AndroidWiringFunctionalTest. Install " +
                "an SDK (platforms + build-tools) to exercise this coverage.",
        )
        writeLocalProperties(projectDir, sdk!!)
    }

    private fun androidRunner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_FLOOR_VERSION)
            .withArguments(*args)
            .forwardOutput()

    /**
     * Writes the standard two-module fixture: `:i18n` (source tree +
     * `net.sarazan.articulate`) and `:app` (`net.sarazan.articulate.android` +
     * `com.android.application`).
     *
     * **`include` order is not evaluation order.** Gradle evaluates sibling
     * projects in alphabetical path order, so `:app` is configured *before*
     * `:i18n` here no matter which the settings file lists first -- meaning
     * every fixture in this class exercises exactly the ordering hazard
     * [ArticulateAndroidPlugin]'s `project.evaluationDependsOn(i18nProjectPath)`
     * exists to defuse: `:i18n` has not yet registered `generateAndroidRes`
     * when AGP fires `:app`'s `onVariants` callbacks.
     *
     * Mutation-verified 2026-07-31: deleting that one call fails four tests in
     * this class, each with the plugin's own (in that state, false)
     * `project ':i18n' has no 'generateAndroidRes' task -- apply
     * net.sarazan.articulate to it first`. The line is load-bearing and is
     * covered; do not "simplify" it away.
     */
    private fun writeTwoModuleFixture(): File {
        writeAndroidSettings(projectDir)
        val i18nDir = File(projectDir.toFile(), "i18n").apply { mkdirs() }
        writeBaseBuildFile(i18nDir.toPath())
        writeValidStringsFixture(i18nDir.toPath())
        writeAndroidAppModule(projectDir)
        return i18nDir
    }

    @Test
    fun `generated Android res is registered as a variant source directory and R string resolves it`() {
        val i18nDir = writeTwoModuleFixture()

        val result = androidRunner(":app:compileDebugJavaWithJavac", ":app:compileReleaseJavaWithJavac").build()

        // Per-variant: both debug and release wired, not just whichever is default.
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDebugJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileReleaseJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:generateAndroidRes")!!.outcome)
        // compileDebugJavaWithJavac succeeding compiles Marker.java, which
        // references R.string.hello -- this only resolves if AGP actually
        // merged the wired directory into the variant's resources and
        // generated a symbol for it. A task merely running would not prove
        // this; an unresolved symbol fails javac, not silently no-ops.

        // Belt-and-suspenders: the generated string also appears somewhere
        // in AGP's own merged-resources output under app/build.
        val mergedHasHello = File(projectDir.toFile(), "app/build").walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .any { it.readText().contains("name=\"hello\"") }
        assertTrue(mergedHasHello, "expected the merged resources under app/build to contain the generated 'hello' string")

        // §4.5's hard requirement: never write into a checked-in res source set.
        val checkedInRes = File(projectDir.toFile(), "app/src/main/res")
        assertFalse(checkedInRes.exists(), "app/src/main/res must never exist -- nothing may write into a checked-in res source set")

        // The strings source tree itself is untouched.
        val sourceStrings = File(i18nDir, "src/main/strings/values/strings.xml")
        assertTrue(
            sourceStrings.readText().contains("<string name=\"hello\">Hello</string>"),
            "the source strings.xml must be untouched by the build",
        )

        // Generated Android res lives only under build/, per §4.4.
        // Where AGP actually puts it. `addGeneratedSourceDirectory(task, wiredWith)`
        // means AGP *owns* the output location: it sets the wired DirectoryProperty
        // itself, overriding whatever convention the task declared. The output
        // therefore lands in the CONSUMING app project's build dir, in a folder
        // named after the task -- not under the :i18n module at all.
        //
        // Verified empirically 2026-07-30 (Google's own "extend AGP" page states
        // the opposite; observation wins). This corrects PLAN.md §4.4, which
        // described the output as living at i18n/build/generated/i18n/res.
        val generated = File(projectDir.toFile(), "app/build/generated/res/generateAndroidRes/values/strings.xml")
        assertTrue(
            generated.isFile,
            "expected AGP-relocated generated res at app/build/generated/res/generateAndroidRes/values/strings.xml",
        )
        val localeGenerated = File(projectDir.toFile(), "app/build/generated/res/generateAndroidRes/values-de/strings.xml")
        assertTrue(localeGenerated.isFile, "per-locale generated res must be relocated alongside the default")

        // And nothing was written under the :i18n module's own build dir, which
        // is what the pre-correction expectation assumed.
        val staleExpectation = File(i18nDir, "build/generated/i18n/res")
        assertFalse(
            staleExpectation.exists(),
            "AGP owns the wired output location, so nothing should land under :i18n's build dir",
        )
    }

    @Test
    fun `i18nProject cross-project task lookup resolves generateAndroidRes from a sibling module`() {
        writeTwoModuleFixture()

        // A task that doesn't even touch generateAndroidRes still forces
        // full project configuration (no configuration-on-demand here), which
        // is where ArticulateAndroidPlugin's androidComponents.onVariants
        // callback performs the cross-project lookup -- so a successful
        // configuration of :app already proves the lookup found :i18n's
        // real, registered generateAndroidRes task, not a stub.
        val result = androidRunner(":app:help").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:help")!!.outcome)
    }

    @Test
    fun `i18nProject pointing at a nonexistent project fails with a clear GradleException`() {
        writeAndroidSettings(projectDir, modules = listOf("app"))
        writeAndroidAppModule(projectDir, i18nProjectPath = ":does-not-exist", withMarker = false)

        val result = androidRunner(":app:help").buildAndFail()

        assertTrue(
            result.output.contains("articulateAndroid.i18nProject is set to ':does-not-exist'") &&
                result.output.contains("but no such project exists"),
            "expected a clear GradleException naming the missing project:\n${result.output}",
        )
    }

    @Test
    fun `i18nProject pointing at a project that has not applied net sarazan articulate fails with a clear GradleException`() {
        writeAndroidSettings(projectDir)
        val i18nDir = File(projectDir.toFile(), "i18n").apply { mkdirs() }
        // Deliberately does not apply net.sarazan.articulate -- no generateAndroidRes task will exist.
        File(i18nDir, "build.gradle").writeText("")
        writeAndroidAppModule(projectDir, withMarker = false)

        val result = androidRunner(":app:help").buildAndFail()

        assertTrue(
            result.output.contains("project ':i18n' has no 'generateAndroidRes' task"),
            "expected a clear GradleException naming the missing task:\n${result.output}",
        )
    }

    @Test
    fun `configuration cache is reused for the Android app path too`() {
        writeTwoModuleFixture()

        val first = androidRunner(":app:compileDebugJavaWithJavac", "--configuration-cache").build()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "expected the first run to store a configuration-cache entry:\n${first.output}",
        )

        val second = androidRunner(":app:compileDebugJavaWithJavac", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Configuration cache entry reused."),
            "expected the second run to reuse the configuration-cache entry on the Android path too:\n${second.output}",
        )
    }
}
