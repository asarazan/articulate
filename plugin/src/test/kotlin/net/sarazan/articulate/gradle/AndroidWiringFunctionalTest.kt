package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.COMPILE_SDK
import net.sarazan.articulate.gradle.FunctionalTestSupport.ANDROID_GRADLE_FLOOR_VERSION
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
 * PLAN.md §4.5/§4.5c: `net.sarazan.articulate.android`'s variant res wiring,
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
 *    shell config. If no SDK is found anywhere, [setUp] delegates to
 *    [FunctionalTestSupport.requireOrSkipAndroidSdk]: a loud skip locally
 *    (via `Assumptions.assumeTrue`, message naming exactly what was checked),
 *    or a hard failure when `ARTICULATE_REQUIRE_ANDROID_SDK=true` (CI always
 *    sets this) -- a green CI run that silently skipped this whole class
 *    would have tested nothing, which is precisely the failure mode that env
 *    var exists to convert into a loud one.
 *  - This is the matrix's floor cell: AGP 9.1.0 (D9, REVISED 2026-08-03) is
 *    not verified against this repo's *building* Gradle (9.5.0), so every
 *    fixture here pins `.withGradleVersion(ANDROID_GRADLE_FLOOR_VERSION)`
 *    (9.3.1), exactly like [GradleFloorFunctionalTest] already does for the
 *    non-Android plugin's own floor -- this suite is deliberately a
 *    floor-only exercise, not a "whatever's on this machine" one. See
 *    [AndroidWiringGradleCurrentFunctionalTest] for the wrapper-Gradle cell.
 *
 * What's now genuinely verified (was previously only compile-checked against
 * the AGP jar, never run):
 *  - `variant.sources.res?.addStaticSourceDirectory(path)` (PLAN.md §4.5c),
 *    fed a path resolved eagerly from `articulateAndroidResIncoming` inside
 *    `onVariants`, actually registers `:i18n`'s real, always-on-disk strings
 *    source directory as a resource source for real debug *and* release
 *    variants -- proven by compiling a Java source that references
 *    `R.string.hello`, which only resolves if AGP's resource merge and
 *    R-class generation actually picked up the wired directory (a stronger
 *    proof than asserting a task merely ran).
 *  - [ArticulateAndroidExtension.i18nProject]'s dependency-based resolution
 *    (PLAN.md §4.5/§13 -- not a cross-project task lookup any more) resolves
 *    `:i18n`'s strings source tree from a real sibling module in a real
 *    multi-module build, and its two failure paths (missing project; project
 *    that hasn't applied `net.sarazan.articulate`) fail loudly with Gradle's
 *    own resolution diagnostics, which name the project path.
 *  - §4.5c's asymmetry: resolving `articulateAndroidResIncoming` for a path
 *    alone (any configuration, e.g. `:app:help`) runs `:i18n:validateStrings`
 *    NEVER; only a real build, via `preBuild.dependsOn(configuration)`, does.
 *  - Nothing ever writes into a checked-in `res` source set -- `app/src/main/res`
 *    never exists, and the strings source tree is untouched after the build.
 *  - There is no copy anywhere any more: `ResolveArticulateAndroidResTask`
 *    and its output directory are deleted (§4.5c point 2).
 */
class AndroidWiringFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        val sdk = requireOrSkipAndroidSdk()
        writeLocalProperties(projectDir, sdk)
    }

    private fun androidRunner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withGradleVersion(ANDROID_GRADLE_FLOOR_VERSION)
            .withArguments(*args)
            .forwardOutput()

    /**
     * Writes the standard two-module fixture: `:i18n` (source tree +
     * `net.sarazan.articulate`) and `:app` (`net.sarazan.articulate.android` +
     * `com.android.application`).
     *
     * **`include` order is not evaluation order.** Gradle evaluates sibling
     * projects in alphabetical path order, so `:app` is configured *before*
     * `:i18n` here no matter which the settings file lists first. Before the
     * §4.5/§13 redesign, this ordering was a real hazard the plugin had to
     * defuse explicitly (`project.evaluationDependsOn(i18nProjectPath)`),
     * because the old cross-project task lookup ran eagerly, inside
     * `onVariants`. It is no longer a hazard at all: the dependency-based
     * mechanism resolves lazily, at whatever point `articulateAndroidResIn`'s
     * files are actually needed -- by then, every project's tasks are
     * registered regardless of evaluation order, so no explicit ordering
     * call is needed any more. This fixture's `:app`-before-`:i18n` order is
     * kept anyway, as a standing check that the redesign really doesn't
     * care.
     */
    private fun writeTwoModuleFixture(): File {
        writeAndroidSettings(projectDir)
        val i18nDir = File(projectDir.toFile(), "i18n").apply { mkdirs() }
        writeBaseBuildFile(i18nDir.toPath())
        writeValidStringsFixture(i18nDir.toPath())
        writeAndroidAppModule(projectDir)
        return i18nDir
    }

    /**
     * PLAN.md §4.5c: `ResolveArticulateAndroidResTask` and its selective copy
     * are deleted. There is no longer any filtering step between :i18n's raw
     * source directory and AGP's resource merge -- `addStaticSourceDirectory`
     * registers `:i18n`'s real `src/main/strings` tree **directly** as a res
     * source for `:app`, whatever is in it.
     *
     * §4.3 explicitly blesses pointing `stringsDir` at a real
     * `app/src/main/res`-shaped directory, which routinely holds `colors.xml`,
     * `dimens.xml`, plus editor litter like `.DS_Store`. §4.5c did not rule on
     * what happens to those now that there is no copy to filter them out --
     * this test exists to observe and pin the actual behavior, not to assert
     * an outcome nobody verified. **Observed 2026-08-05, real AGP 9.1 build:**
     * `colors.xml` and `dimens.xml` beside `strings.xml` are merged into the
     * app's resources exactly like any other file AGP finds under a
     * `values`-qualified directory in a registered res source directory --
     * `colorPrimary`/`gutter` compile as
     * ordinary app resources, and `.DS_Store` is silently ignored by AGP's
     * resource merger (it already ignores non-resource files by convention,
     * independent of anything Articulate does). This is a genuine behavior
     * change from §4.5b's copy-based approach, which filtered to
     * `strings.xml` only: a consumer whose i18n source tree contains
     * presentation resources now has them flow into the app's build. Nothing
     * here fails or errors -- the risk is a same-name collision with the
     * app's own resources, which is an ordinary Android duplicate-resource
     * build failure, not a new failure mode this plugin introduces.
     */
    @Test
    fun `presentation files and editor litter beside strings xml flow directly into the app's merged resources`() {
        val i18nDir = writeTwoModuleFixture()
        val values = File(i18nDir, "src/main/strings/values")
        File(values, "colors.xml").writeText(
            """
            <resources>
                <color name="colorPrimary">#FFFFFF</color>
            </resources>
            """.trimIndent(),
        )
        File(values, "dimens.xml").writeText(
            """
            <resources>
                <dimen name="gutter">8dp</dimen>
            </resources>
            """.trimIndent(),
        )
        File(values, ".DS_Store").writeBytes(byteArrayOf(0, 0, 0, 1))

        val javaDir = File(projectDir.toFile(), "app/src/main/java/net/sarazan/articulate/fixture/app").apply { mkdirs() }
        File(javaDir, "PresentationMarker.java").writeText(
            """
            package net.sarazan.articulate.fixture.app;

            /** Compiles only if R.color.colorPrimary and R.dimen.gutter resolve too, not just R.string -- proof
             * presentation files beside strings.xml reach AGP's merge now that there is no filtering copy (PLAN.md §4.5c). */
            public final class PresentationMarker {
                public static final int COLOR_PRIMARY_ID = R.color.colorPrimary;
                public static final int GUTTER_ID = R.dimen.gutter;
                private PresentationMarker() {}
            }
            """.trimIndent(),
        )

        val result = androidRunner(":app:compileDebugJavaWithJavac").build()

        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":app:compileDebugJavaWithJavac")!!.outcome,
            "expected R.color.colorPrimary and R.dimen.gutter (declared beside strings.xml in :i18n's source " +
                "tree) to compile -- proof they reached AGP's resource merge with no filtering step:\n${result.output}",
        )
    }

    @Test
    fun `generated Android res is registered via DSL srcDir at finalizeDsl and R string resolves it`() {
        val i18nDir = writeTwoModuleFixture()

        val result = androidRunner(":app:compileDebugJavaWithJavac", ":app:compileReleaseJavaWithJavac").build()

        // Per-variant: both debug and release wired, not just whichever is default.
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDebugJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileReleaseJavaWithJavac")!!.outcome)
        // PLAN.md §4.5c point 3: :i18n's gate is validateStrings, forced into
        // this real build by preBuild.dependsOn(articulateAndroidResIncoming)
        // -- not by any task's @InputFiles any more, since no task resolves
        // this configuration for its file contents.
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:validateStrings")!!.outcome)
        // compileDebugJavaWithJavac succeeding compiles Marker.java, which
        // references R.string.hello -- this only resolves if AGP actually
        // merged the wired directory into the variant's resources and
        // generated a symbol for it. A task merely running would not prove
        // this; an unresolved symbol fails javac, not silently no-ops.

        // §4.5's hard requirement: never write into a checked-in res source set.
        val checkedInRes = File(projectDir.toFile(), "app/src/main/res")
        assertFalse(checkedInRes.exists(), "app/src/main/res must never exist -- nothing may write into a checked-in res source set")

        // The strings source tree itself is untouched -- doubly true now
        // that there is no copy task at all, only a static registration of
        // this exact directory.
        val sourceStrings = File(i18nDir, "src/main/strings/values/strings.xml")
        assertTrue(
            sourceStrings.readText().contains("<string name=\"hello\">Hello</string>"),
            "the source strings.xml must be untouched by the build",
        )

        // PLAN.md §4.5c point 2: ResolveArticulateAndroidResTask and the
        // addGeneratedSourceDirectory relocation are both deleted -- there is
        // no longer any copy of :i18n's res anywhere under app/build. This is
        // the load-bearing assertion distinguishing §4.5c from §4.5b: the old
        // path must be genuinely ABSENT, not merely unchecked.
        assertFalse(
            File(projectDir.toFile(), "app/build/generated/res/resolveArticulateAndroidRes").exists(),
            "resolveArticulateAndroidRes's task and its relocated output directory are both deleted by §4.5c -- " +
                "this path must not exist any more",
        )

        // PLAN.md §4.5b, still true under §4.5c: :i18n no longer generates
        // ANY Android res copy of its own -- GenerateAndroidResTask is
        // deleted, and the consumable configuration publishes :i18n's real
        // source directory directly. There is nothing under :i18n/build for
        // the Android path at all any more.
        assertFalse(
            File(i18nDir, "build/generated/i18n/res").exists(),
            "GenerateAndroidResTask is deleted -- :i18n must not generate its own Android res copy any more",
        )
    }

    /**
     * PLAN.md §4.5c's landmine, tested directly: `addStaticSourceDirectory`
     * carries no task dependency, so resolving `articulateAndroidResIncoming`
     * purely to read a path (which this plugin's `afterEvaluate` callback
     * does for *any* requested task, including `:app:help` -- afterEvaluate
     * runs during ordinary project configuration, whether or not the
     * requested task ever touches this configuration as a real input) must
     * NOT pull `:i18n:validateStrings` into the task graph. Only a real
     * build -- anything that runs `preBuild` -- does that, via the explicit
     * `preBuild.dependsOn(configuration)` edge. This is the asymmetry
     * §4.5c's whole design rests on; this test proves both halves of it in
     * one fixture: `:app:help` succeeds (dependency-based resolution works,
     * no cross-project task lookup) AND `:i18n:validateStrings` never ran.
     */
    @Test
    fun `resolving articulateAndroidResIncoming for a path alone does not run validateStrings`() {
        writeTwoModuleFixture()

        val result = androidRunner(":app:help").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:help")!!.outcome)
        assertTrue(
            result.task(":i18n:validateStrings") == null,
            "expected :i18n:validateStrings NOT to run for a plain configuration-only task -- resolving " +
                "articulateAndroidResIncoming for a path must execute nothing (PLAN.md §4.5c): ${result.output}",
        )
    }

    /**
     * PLAN.md §4.5c's acceptance criterion, "proven red first": an invalid
     * strings source (`<string-array>`, a documented D6 hard error) must fail
     * the APP build -- not merely `:i18n`'s own build -- with Articulate's
     * own diagnostic (not a raw AGP resource-merge error, and not silently
     * accepted), because `preBuild.dependsOn(articulateAndroidResIncoming)`
     * pulls `:i18n:validateStrings` into `:app`'s task graph before
     * compilation. This is the whole point of restoring the edge §4.5c's
     * landmine would otherwise silently drop.
     *
     * **Proven red first, 2026-08-05 (this file's git history is not
     * available to this agent, so the proof is recorded here instead):**
     * backed up `ArticulateAndroidPlugin.kt`, deleted the
     * `project.tasks.named("preBuild").configure { it.dependsOn(articulateAndroidResIn) }`
     * block entirely, reran exactly this test. Result: `UnexpectedBuildSuccess`
     * -- `:app:compileDebugJavaWithJavac` **succeeded** with the invalid
     * `<string-array>` still in `:i18n`'s source tree, because
     * `:i18n:validateStrings` never entered `:app`'s task graph at all (the
     * exact landmine PLAN.md §4.5c's premise describes: `addStaticSourceDirectory`
     * carries no task dependency, so with the edge gone nothing pulls the
     * gate in). Restored the file from the backup; reran the full class,
     * confirmed all tests green again, including this one.
     */
    @Test
    fun `an invalid string in the i18n fixture fails the app build with Articulate's own diagnostic`() {
        val i18nDir = writeTwoModuleFixture()
        File(i18nDir, "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello</string>
                <string-array name="days">
                    <item>Mon</item>
                    <item>Tue</item>
                </string-array>
            </resources>
            """.trimIndent(),
        )

        val result = androidRunner(":app:compileDebugJavaWithJavac").buildAndFail()

        assertEquals(
            TaskOutcome.FAILED,
            result.task(":i18n:validateStrings")!!.outcome,
            "expected :i18n:validateStrings to run (pulled in via preBuild.dependsOn) and fail the app build:\n${result.output}",
        )
        assertTrue(
            result.output.contains("strings.xml") &&
                result.output.contains("<string-array>") &&
                result.output.contains("days_0"),
            "expected Articulate's own diagnostic -- naming the file (strings.xml), the offending construct " +
                "(<string-array>), and the fix (split into 'days_0', 'days_1', ...) -- not a raw AGP resource " +
                "merge error:\n${result.output}",
        )
    }

    @Test
    fun `i18nProject pointing at a nonexistent project fails with a clear resolution error naming that project`() {
        writeAndroidSettings(projectDir, modules = listOf("app"))
        writeAndroidAppModule(projectDir, i18nProjectPath = ":does-not-exist", withMarker = false)

        // PLAN.md §4.5c THIRD AMENDMENT (2026-08-08): the configuration is
        // never resolved at configuration time any more -- doing so was
        // bisection-proven to strip kotlin-stdlib from the consuming module's
        // IDE model. Resolution happens only inside verifyArticulateWiring's
        // task action, wired via preBuild -- so the failure surfaces on the
        // first real build, not on :app:help. Same clear message, new moment.
        val result = androidRunner(":app:preBuild").buildAndFail()

        assertTrue(
            result.output.contains("Project with path ':does-not-exist' could not be found"),
            "expected a clear resolution error naming the missing project:\n${result.output}",
        )
    }

    @Test
    fun `i18nProject pointing at a project that has not applied net sarazan articulate fails with a clear resolution error`() {
        writeAndroidSettings(projectDir)
        val i18nDir = File(projectDir.toFile(), "i18n").apply { mkdirs() }
        // Deliberately does not apply net.sarazan.articulate -- no
        // articulateAndroidResElements consumable configuration will exist.
        File(i18nDir, "build.gradle").writeText("")
        writeAndroidAppModule(projectDir, withMarker = false)

        val result = androidRunner(":app:preBuild").buildAndFail()

        assertTrue(
            result.output.contains("Could not resolve project :i18n") &&
                result.output.contains("No matching variant of project :i18n was found") &&
                result.output.contains("net.sarazan.articulate.android-res"),
            "expected a clear resolution error naming the misconfigured project and the missing attribute:\n${result.output}",
        )
    }

    @Test
    fun `applying both plugin IDs to a single module resolves its own res without a raw CircularReferenceException`() {
        // PLAN.md §4.5's explicit requirement: applying both plugins to one
        // module must not deadlock or produce a raw Gradle error naming
        // nothing about Articulate (the pre-redesign implementation threw
        // org.gradle.api.CircularReferenceException here, via
        // project.evaluationDependsOn(project.path) -- a call this
        // implementation no longer makes at all). articulateAndroid.i18nProject
        // is pointed at the module's own path, matching a single-module app
        // that also owns its own strings.
        writeAndroidSettings(projectDir, modules = listOf("app"))
        val appDir = File(projectDir.toFile(), "app").apply { mkdirs() }
        File(appDir, "build.gradle").writeText(
            """
            plugins {
                id 'net.sarazan.articulate'
                id 'net.sarazan.articulate.android'
            }

            apply plugin: 'com.android.application'

            android {
                namespace 'net.sarazan.articulate.fixture.selfapp'
                compileSdk $COMPILE_SDK

                defaultConfig {
                    applicationId 'net.sarazan.articulate.fixture.selfapp'
                    minSdk 24
                    targetSdk $COMPILE_SDK
                }
            }

            articulate {
                sourceLanguage = 'en'
                ios {
                    catalog = file('ios/Shared.xcstrings')
                }
            }

            articulateAndroid {
                i18nProject = ':app'
            }
            """.trimIndent(),
        )
        writeValidStringsFixture(appDir.toPath())
        val mainDir = File(appDir, "src/main").apply { mkdirs() }
        File(mainDir, "AndroidManifest.xml").writeText(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application />
            </manifest>
            """.trimIndent(),
        )
        val javaDir = File(mainDir, "java/net/sarazan/articulate/fixture/selfapp").apply { mkdirs() }
        File(javaDir, "Marker.java").writeText(
            """
            package net.sarazan.articulate.fixture.selfapp;

            /** Compiles only if R.string.hello resolves -- proof the self-applied module's own strings tree reached AGP's merge. */
            public final class Marker {
                public static final int HELLO_STRING_ID = R.string.hello;
                private Marker() {}
            }
            """.trimIndent(),
        )

        val result = androidRunner(":app:compileDebugJavaWithJavac").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDebugJavaWithJavac")!!.outcome)
        // PLAN.md §4.5c: validateStrings is the gate, forced in via
        // preBuild.dependsOn(articulateAndroidResIncoming) -- same-project
        // self-application means both the resolvable AND consumable
        // configuration, and both plugins' tasks, live in :app itself.
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:validateStrings")!!.outcome)
        assertFalse(result.output.contains("CircularReferenceException"))
        assertFalse(
            File(appDir, "build/generated/res/resolveArticulateAndroidRes").exists(),
            "resolveArticulateAndroidRes is deleted by §4.5c -- must not exist even in the self-apply case",
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
