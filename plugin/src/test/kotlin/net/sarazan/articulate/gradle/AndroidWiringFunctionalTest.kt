package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.COMPILE_SDK
import net.sarazan.articulate.gradle.FunctionalTestSupport.GRADLE_FLOOR_VERSION
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
 *    shell config. If no SDK is found anywhere, [setUp] delegates to
 *    [FunctionalTestSupport.requireOrSkipAndroidSdk]: a loud skip locally
 *    (via `Assumptions.assumeTrue`, message naming exactly what was checked),
 *    or a hard failure when `ARTICULATE_REQUIRE_ANDROID_SDK=true` (CI always
 *    sets this) -- a green CI run that silently skipped this whole class
 *    would have tested nothing, which is precisely the failure mode that env
 *    var exists to convert into a loud one.
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
 *  - [ArticulateAndroidExtension.i18nProject]'s dependency-based resolution
 *    (PLAN.md §4.5/§13 -- not a cross-project task lookup any more) resolves
 *    `:i18n`'s generated res from a real sibling module in a real
 *    multi-module build, and its two failure paths (missing project; project
 *    that hasn't applied `net.sarazan.articulate`) fail loudly with Gradle's
 *    own resolution diagnostics, which name the project path.
 *  - Nothing ever writes into a checked-in `res` source set -- `app/src/main/res`
 *    never exists, and the strings source tree is untouched after the build.
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
     * PLAN.md §4.5b made [ResolveArticulateAndroidResTask]'s copy **selective**
     * -- only `strings.xml` under each locale directory -- and that selectivity
     * is load-bearing in a way nothing else covered until this test.
     *
     * Before §4.5b the task copied a tree `generateAndroidRes` had already
     * filtered, so a blanket `copyRecursively` was harmless. §4.5b feeds it the
     * consumer's **raw source directory** instead, and §4.3 explicitly blesses
     * pointing `stringsDir` at a real `app/src/main/res` -- which routinely holds
     * `colors.xml`, `dimens.xml`, `styles.xml`, plus editor litter like
     * `.DS_Store`. A blanket copy drags all of it into the app's generated res,
     * where a `colors.xml` carrying a name the app also defines is a duplicate
     * resource: a build failure at best, a silent override at worst.
     *
     * The regression this pins is therefore invisible to every other test --
     * they all use a fixture containing nothing but `strings.xml`, so blanket
     * and selective copying are indistinguishable. Mutation-proven: restoring
     * `copyRecursively` fails this and nothing else.
     */
    @Test
    fun `presentation files and editor litter beside strings xml are not copied into the app's generated res`() {
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

        androidRunner(":app:resolveArticulateAndroidRes").build()

        val generatedValues = File(projectDir.toFile(), "app/build/generated/res/resolveArticulateAndroidRes/values")
        val copied = generatedValues.listFiles().orEmpty().map { it.name }.sorted()
        assertTrue(
            copied.contains("strings.xml"),
            "guard: strings.xml must be copied, otherwise the assertions below pass for the wrong reason. Got: $copied",
        )
        assertEquals(
            listOf("strings.xml"),
            copied,
            "only values*/strings.xml may reach the app's generated res -- anything else can collide with the " +
                "app's own resources (PLAN.md §4.5b). Got: $copied",
        )
    }

    @Test
    fun `generated Android res is registered as a variant source directory and R string resolves it`() {
        val i18nDir = writeTwoModuleFixture()

        val result = androidRunner(":app:compileDebugJavaWithJavac", ":app:compileReleaseJavaWithJavac").build()

        // Per-variant: both debug and release wired, not just whichever is default.
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileDebugJavaWithJavac")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileReleaseJavaWithJavac")!!.outcome)
        // PLAN.md §4.5b: GenerateAndroidResTask is deleted -- :i18n's gate is
        // now validateStrings, forced into this real build by resolveArticulateAndroidRes's
        // @InputFiles on the resolvable configuration (builtBy).
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:validateStrings")!!.outcome)
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
        // named after the WIRED task -- not named after generateAndroidRes: the
        // task AGP wires is now net.sarazan.articulate.android's own
        // ResolveArticulateAndroidResTask ("resolveArticulateAndroidRes"),
        // registered in the app module, not the i18n-owning task directly.
        //
        // Verified empirically 2026-07-30 (Google's own "extend AGP" page states
        // the opposite; observation wins). This corrects PLAN.md §4.4, which
        // described the output as living at i18n/build/generated/i18n/res.
        val generated = File(projectDir.toFile(), "app/build/generated/res/resolveArticulateAndroidRes/values/strings.xml")
        assertTrue(
            generated.isFile,
            "expected AGP-relocated generated res at app/build/generated/res/resolveArticulateAndroidRes/values/strings.xml",
        )
        val localeGenerated = File(projectDir.toFile(), "app/build/generated/res/resolveArticulateAndroidRes/values-de/strings.xml")
        assertTrue(localeGenerated.isFile, "per-locale generated res must be relocated alongside the default")

        // PLAN.md §4.5b, genuinely different from the pre-4.5b invariant:
        // :i18n no longer generates ANY Android res copy of its own --
        // GenerateAndroidResTask is deleted, and the consumable
        // configuration now publishes :i18n's real source directory
        // directly. There is nothing under :i18n/build for the Android path
        // at all any more.
        assertFalse(
            File(i18nDir, "build/generated/i18n/res").exists(),
            "GenerateAndroidResTask is deleted -- :i18n must not generate its own Android res copy any more",
        )
    }

    @Test
    fun `articulateAndroidResIncoming resolves the sibling i18n module's generated res via dependency resolution, not a task lookup`() {
        val i18nDir = writeTwoModuleFixture()

        // Unlike the pre-redesign implementation, plain configuration (e.g.
        // `:app:help`) no longer touches the cross-project dependency at all
        // -- resolution is now genuinely lazy, deferred to whenever
        // resolveArticulateAndroidRes's inputs are actually needed (PLAN.md
        // §4.5/§13). Requesting that task directly is therefore the
        // narrowest real proof the dependency-based mechanism resolves
        // :i18n's generated res correctly, with no `dependsOn` declared
        // anywhere in ArticulateAndroidPlugin -- the ordering is carried
        // implicitly by the configuration's artifact `builtBy`.
        val result = androidRunner(":app:resolveArticulateAndroidRes").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveArticulateAndroidRes")!!.outcome)
        // PLAN.md §4.5b: GenerateAndroidResTask is deleted -- validateStrings
        // is the gate resolveArticulateAndroidRes's @InputFiles forces in.
        assertEquals(TaskOutcome.SUCCESS, result.task(":i18n:validateStrings")!!.outcome)

        // AGP relocates resolveArticulateAndroidRes's output as soon as it
        // creates variants during project configuration (§4.4/§4.5), which
        // happens regardless of which task was actually requested -- so even
        // this narrow, non-compile invocation already sees the relocated
        // path, not resolveArticulateAndroidRes's own build/generated/articulate/res
        // convention.
        val resolved = File(projectDir.toFile(), "app/build/generated/res/resolveArticulateAndroidRes/values/strings.xml")
        assertTrue(
            resolved.isFile && resolved.readText().contains("<string name=\"hello\">Hello</string>"),
            "expected resolveArticulateAndroidRes to have materialized :i18n's generated res, sourced via " +
                "dependency resolution rather than a cross-project task lookup: $resolved",
        )
        // Sanity: the source module used for this resolution really is :i18n.
        assertTrue(i18nDir.name == "i18n")
    }

    @Test
    fun `i18nProject pointing at a nonexistent project fails with a clear resolution error naming that project`() {
        writeAndroidSettings(projectDir, modules = listOf("app"))
        writeAndroidAppModule(projectDir, i18nProjectPath = ":does-not-exist", withMarker = false)

        // Resolution is lazy (§4.5/§13's redesign) -- unlike the old
        // cross-project task lookup, plain configuration (`:app:help`) no
        // longer touches it. Requesting the task that actually needs the
        // resolved res is what surfaces the failure now. The message is
        // Gradle's own dependency-resolution diagnostic, not a custom
        // Articulate-authored one (§4.5's requirements are about *how* the
        // lookup happens -- no cross-project task-container access, no
        // cross-classloader class identity -- not about who authors the
        // failure text), and it already names the exact project path.
        val result = androidRunner(":app:resolveArticulateAndroidRes").buildAndFail()

        assertTrue(
            result.output.contains("Could not determine the dependencies of task ':app:resolveArticulateAndroidRes'") &&
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

        val result = androidRunner(":app:resolveArticulateAndroidRes").buildAndFail()

        assertTrue(
            result.output.contains("Could not resolve project :i18n") &&
                result.output.contains("No matching variant of project :i18n was found") &&
                result.output.contains("net.sarazan.articulate.android-res"),
            "expected a clear resolution error naming the misconfigured project and the missing attribute:\n${result.output}",
        )
    }

    @Test
    fun `applying both plugin IDs to a single module resolves its own generated res without a raw CircularReferenceException`() {
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

        val result = androidRunner(":app:resolveArticulateAndroidRes").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveArticulateAndroidRes")!!.outcome)
        // PLAN.md §4.5b: GenerateAndroidResTask is deleted -- validateStrings is the gate.
        assertEquals(TaskOutcome.SUCCESS, result.task(":app:validateStrings")!!.outcome)
        assertFalse(result.output.contains("CircularReferenceException"))
        val resolved = File(appDir, "build/generated/res/resolveArticulateAndroidRes/values/strings.xml")
        assertTrue(resolved.isFile, "expected the self-applied module's own generated res to resolve: $resolved")
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
