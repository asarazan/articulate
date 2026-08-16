package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.runner
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeBaseBuildFile
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeSettings
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeValidStringsFixture
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
 * PLAN.md §4.8: task wiring, up-to-dateness, and `warningsAsErrors`
 * behavior for `validateStrings`, `generateXcstrings`, and the
 * `generateStrings` aggregate. Runs under the *building* Gradle (9.5.0); the
 * floor Gradle (8.7) is exercised separately by [GradleFloorFunctionalTest].
 *
 * No AGP involved anywhere in this file -- `net.sarazan.articulate` has none
 * on its classpath by design (D10), so every test here runs cleanly on a
 * machine with no Android SDK.
 */
class GenerateTasksFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        writeSettings(projectDir)
        writeBaseBuildFile(projectDir)
        writeValidStringsFixture(projectDir)
    }

    @Test
    fun `generateStrings aggregate invokes generateXcstrings and validateStrings and produces the catalog`() {
        // PLAN.md §4.5b: GenerateAndroidResTask (and its build/generated/i18n/res
        // copy) is deleted -- the strings source tree IS already valid Android
        // resource layout, so there is nothing left to generate for the
        // Android path, only validateStrings' gate to run.
        val result = runner(projectDir, "generateStrings").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":validateStrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateXcstrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStrings")!!.outcome)

        val catalog = File(projectDir.toFile(), "ios/Shared.xcstrings")
        assertTrue(catalog.isFile, "expected ios/Shared.xcstrings to be written")
        val catalogText = catalog.readText()
        assertTrue(catalogText.contains("\"hello\""), "catalog should contain the 'hello' key:\n$catalogText")
        assertTrue(catalogText.contains("\"sourceLanguage\" : \"en\""), "catalog should declare sourceLanguage en:\n$catalogText")

        // §4.5b's verified premise, negative half: there is no more generated
        // Android res tree anywhere under build/ for :i18n to own.
        assertFalse(
            File(projectDir.toFile(), "build/generated/i18n/res").exists(),
            "GenerateAndroidResTask is deleted -- nothing should generate build/generated/i18n/res any more",
        )
    }

    @Test
    fun `second run with no source change reports generateXcstrings UP-TO-DATE and validateStrings re-running`() {
        // validateStrings is deliberately @UntrackedTask (PLAN.md §4.5b/§5's
        // "a gate must always re-verify" principle, same as verifyStrings) --
        // it can never report UP-TO-DATE, so unlike the deleted
        // GenerateAndroidResTask this is genuinely different behavior, not a
        // regression: generateXcstrings (still CacheableTask) keeps its
        // up-to-date semantics; validateStrings re-runs (and re-succeeds)
        // every time it's scheduled.
        runner(projectDir, "generateStrings").build()
        val second = runner(projectDir, "generateStrings").build()

        assertEquals(TaskOutcome.SUCCESS, second.task(":validateStrings")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateXcstrings")!!.outcome)
    }

    @Test
    fun `editing one locale's strings xml re-runs generateXcstrings and re-validates`() {
        runner(projectDir, "generateStrings").build()

        // Mutate-and-observe (per the process rules): this must be able to
        // fail -- if the edit below didn't actually change anything Gradle's
        // input snapshot cares about, the second run would wrongly report
        // UP-TO-DATE and this assertion would catch it.
        File(projectDir.toFile(), "src/main/strings/values-de/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Servus</string>
                <string name="greeting">Servus, %1${'$'}s!</string>
            </resources>
            """.trimIndent(),
        )

        val second = runner(projectDir, "generateStrings").build()
        assertEquals(TaskOutcome.SUCCESS, second.task(":validateStrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, second.task(":generateXcstrings")!!.outcome)

        val catalogText = File(projectDir.toFile(), "ios/Shared.xcstrings").readText()
        assertTrue(catalogText.contains("Servus"), "regenerated catalog should reflect the edited German string:\n$catalogText")
    }

    @Test
    fun `diagnostics are advisory by default -- build succeeds with a warning logged`() {
        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello</string>
                <string name="irregular.key">Something</string>
            </resources>
            """.trimIndent(),
        )
        File(projectDir.toFile(), "src/main/strings/values-de/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hallo</string>
                <string name="irregular.key">Etwas</string>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateXcstrings", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateXcstrings")!!.outcome)
        assertTrue(
            result.output.contains("irregular.key") && result.output.contains("contains"),
            "expected the irregular-key diagnostic to be logged:\n${result.output}",
        )
    }

    @Test
    fun `warningsAsErrors escalates the same diagnostic to a build failure`() {
        writeBaseBuildFile(projectDir, extraConfig = "warningsAsErrors = true")
        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello</string>
                <string name="irregular.key">Something</string>
            </resources>
            """.trimIndent(),
        )
        File(projectDir.toFile(), "src/main/strings/values-de/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hallo</string>
                <string name="irregular.key">Etwas</string>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateXcstrings").buildAndFail()
        assertTrue(
            result.output.contains("warningsAsErrors") && result.output.contains("irregular.key"),
            "expected the failure to name warningsAsErrors and the offending key:\n${result.output}",
        )

        // Can-fail check performed by hand during development (per the
        // process rules): with warningsAsErrors left at its default false,
        // this exact fixture is exercised by the test above and passes --
        // confirming the failure above is actually caused by the flag, not
        // by something else in the fixture.
        assertFalse(result.output.contains("BUILD SUCCESSFUL"))
    }

    /**
     * PLAN.md §4.7, verbatim: "any diagnostic fails the task with a message
     * listing **every offender** -- not just the first, so one build surfaces
     * the whole set."
     *
     * The single-offender test above cannot distinguish "lists every offender"
     * from "lists the first offender and stops" -- with one diagnostic the two
     * behaviors are identical output. This one uses three, and asserts all
     * three appear in the failure message itself (not merely in the WARN log
     * that precedes it, which is a separate loop).
     */
    @Test
    fun `warningsAsErrors names every offender, not just the first`() {
        writeBaseBuildFile(projectDir, extraConfig = "warningsAsErrors = true")
        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello</string>
                <string name="first.offender">One</string>
                <string name="second-offender">Two</string>
                <string name="third.offender">Three</string>
            </resources>
            """.trimIndent(),
        )
        File(projectDir.toFile(), "src/main/strings/values-de").deleteRecursively()

        val result = runner(projectDir, "generateXcstrings").buildAndFail()

        // Scope the assertion to the aggregated failure message, so a match
        // inside the preceding per-diagnostic WARN lines cannot satisfy it.
        val marker = "warningsAsErrors is true and 3 diagnostic(s) were found:"
        val failureMessage = result.output.substringAfter(marker, "")
        assertTrue(failureMessage.isNotEmpty(), "expected the aggregated failure message:\n${result.output}")
        for (key in listOf("first.offender", "second-offender", "third.offender")) {
            assertTrue(
                failureMessage.contains(key),
                "the aggregated failure must name every offender, but '$key' is missing:\n$failureMessage",
            )
        }
    }

    /**
     * VERBATIM remains unimplemented (PLAN.md D4/D7): the build fails at
     * configuration time (before any task executes) and the message names
     * the value, that ERROR/STRIP are the supported ones, and PLAN.md §2.2/D4.
     */
    @Test
    fun `markupPolicy set to VERBATIM fails fast at configuration time`() {
        writeBaseBuildFile(
            projectDir,
            extraConfig = "markupPolicy = net.sarazan.articulate.core.convert.MarkupPolicy.VERBATIM",
        )

        val result = runner(projectDir, "help").buildAndFail()

        assertTrue(
            result.output.contains("markupPolicy") &&
                result.output.contains("VERBATIM") &&
                result.output.contains("not yet implemented") &&
                result.output.contains("MarkupPolicy.ERROR") &&
                result.output.contains("MarkupPolicy.STRIP") &&
                result.output.contains("D4"),
            "expected a fail-fast message naming the value, ERROR/STRIP as supported, and D4:\n${result.output}",
        )
        // No task should have even started -- this must fail during
        // configuration, not merely as a side effect of some task's action.
        assertFalse(result.output.contains("BUILD SUCCESSFUL"))
    }

    /**
     * PLAN.md D4/D7 (this issue's ruling, 2026-08-10): `STRIP` is now
     * implemented end to end, so setting it must no longer fail fast --
     * this is the regression test for the old "STRIP fails fast" behavior
     * this test replaces. `generateXcstrings` must actually run and the
     * generated catalog must reflect the stripped, span-boundary-corrected
     * text (M2's double-space rule), not just "the build didn't crash".
     */
    @Test
    fun `markupPolicy set to STRIP no longer fails fast and strips inline markup`() {
        writeBaseBuildFile(
            projectDir,
            extraConfig = "markupPolicy = net.sarazan.articulate.core.convert.MarkupPolicy.STRIP",
        )
        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello</string>
                <string name="spaced_span">This <b> is </b> spaced</string>
            </resources>
            """.trimIndent(),
        )
        // The default fixture's values-de/strings.xml still declares "greeting",
        // which values/strings.xml above no longer has -- drop it so this test
        // isn't also exercising K6's orphan-translation check.
        File(projectDir.toFile(), "src/main/strings/values-de").deleteRecursively()

        val result = runner(projectDir, "generateXcstrings").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateXcstrings")!!.outcome)

        val catalogText = File(projectDir.toFile(), "ios/Shared.xcstrings").readText()
        assertTrue(
            catalogText.contains("This  is  spaced"),
            "expected the stripped markup with M2's double-space span-boundary rule applied:\n$catalogText",
        )
    }

    /**
     * The default (`ERROR`, unset) must keep working -- this is the
     * companion negative case proving the tests above are actually caused
     * by the non-default value, not by `writeBaseBuildFile`'s `extraConfig`
     * mechanism itself or some other regression.
     */
    @Test
    fun `markupPolicy left at its default ERROR does not fail fast`() {
        val result = runner(projectDir, "help").build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }

    /**
     * PLAN.md §4.3: the silent-loss bug this milestone fixes. Splitting
     * `values/strings.xml` and `values/plurals.xml` is ordinary Android
     * practice -- Android's resource merger folds both into the same
     * string table -- so a companion file that actually carries
     * localizable content must fail the build loudly, not get silently
     * dropped from the generated output. Exercised through the real Gradle
     * task graph (not just `core`'s corpus), so a regression that only
     * shows up in how `ValidateStringsTask`/`GenerateXcstringsTask` wire
     * up `AndroidToXcstringsConverter` -- e.g. discovery reverting to its
     * own independent `File` filtering -- is caught here too.
     */
    @Test
    fun `a companion file that actually declares localizable content fails generateStrings`() {
        File(projectDir.toFile(), "src/main/strings/values/plurals.xml").writeText(
            """
            <resources>
                <plurals name="cart_item_count">
                    <item quantity="one">%1${'$'}d item</item>
                    <item quantity="other">%1${'$'}d items</item>
                </plurals>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateStrings").buildAndFail()

        assertTrue(
            result.output.contains("plurals.xml") &&
                result.output.contains("not yet supported") &&
                result.output.contains("cart_item_count"),
            "expected the failure to name plurals.xml, 'not yet supported', and the offending key:\n${result.output}",
        )
        assertFalse(result.output.contains("BUILD SUCCESSFUL"))
    }

    /**
     * The companion case: a presentation-only file (`colors.xml`, exactly
     * what a real `app/src/main/res/values/` directory routinely holds)
     * must not break the build.
     *
     * **Rewritten for PLAN.md §4.5b.** The original version of this test
     * asserted `colors.xml` was never copied into `generateAndroidRes`'s
     * output tree -- meaningful when that task existed, but §4.5b deletes it
     * entirely (the strings source tree is used as Android resource layout
     * directly, nothing is copied anywhere by `:i18n` any more), so that
     * specific claim is now vacuously true for every input and would no
     * longer be a real test (the can't-fail-test rule). What's still
     * genuinely testable and still the point of this fixture: a
     * presentation-only companion file must not make `validateStrings` (the
     * task that replaced `generateAndroidRes`'s validation role) error, and
     * must not leak into the generated catalog as a spurious key.
     *
     * **Extended for the 2026-08-03 ruling** (PLAN.md §4.3's amended table
     * row, §4.5c, CONVERSIONS.md K8): a presentation-only companion file is
     * no longer *silently* passed over -- it must still succeed (warning is
     * not error), but the build output must now carry a warning naming
     * `colors.xml` and the Android-ships/iOS-ignores asymmetry, through the
     * same [net.sarazan.articulate.core.diagnostics.Diagnostic] channel
     * `--info` already surfaces for other diagnostics in this file.
     */
    @Test
    fun `a presentation-only companion file like colors xml does not fail validateStrings or leak into the catalog, but warns`() {
        File(projectDir.toFile(), "src/main/strings/values/colors.xml").writeText(
            """
            <resources>
                <color name="colorPrimary">#FFFFFF</color>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateStrings", "--info").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":validateStrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateXcstrings")!!.outcome)

        val catalogText = File(projectDir.toFile(), "ios/Shared.xcstrings").readText()
        assertTrue(catalogText.contains("\"hello\""), "expected the real string key to still be present:\n$catalogText")
        assertFalse(
            catalogText.contains("colorPrimary"),
            "colors.xml is presentation-only and must never leak into the generated catalog as a key",
        )

        assertTrue(
            result.output.contains("colors.xml") && result.output.contains("ignored entirely on iOS"),
            "expected the presentation-file warning to name colors.xml and state the Android-ships/" +
                "iOS-ignores asymmetry:\n${result.output}",
        )
    }

    /**
     * The promotion half of the 2026-08-03 ruling: `warningsAsErrors = true`
     * must turn the same presentation-file warning into a build failure that
     * names `colors.xml`, exactly like every other diagnostic PLAN.md §4.7
     * already promotes.
     */
    @Test
    fun `warningsAsErrors promotes the presentation-file warning for colors xml to a build failure`() {
        writeBaseBuildFile(projectDir, extraConfig = "warningsAsErrors = true")
        File(projectDir.toFile(), "src/main/strings/values/colors.xml").writeText(
            """
            <resources>
                <color name="colorPrimary">#FFFFFF</color>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateStrings").buildAndFail()

        assertTrue(
            result.output.contains("warningsAsErrors") && result.output.contains("colors.xml"),
            "expected the failure to name warningsAsErrors and colors.xml:\n${result.output}",
        )
        assertFalse(result.output.contains("BUILD SUCCESSFUL"))
    }

    /**
     * PLAN.md §4.5d razor audit: `localeOverrides` is unit-tested at the
     * core mapper level ([net.sarazan.articulate.core.locale.AndroidLocaleMapperTest]),
     * and `ArticulatePlugin` wires `task.localeOverrides.set(extension.localeOverrides)`
     * on three tasks -- but no functional test ever exercised that wiring
     * through the Gradle DSL end to end. Without the override, a
     * `values-zh-rSG` directory maps to `zh-SG` (no `ZH_REGION_TO_SCRIPT`
     * entry for `SG`, unlike `CN`/`TW`/`HK`); with `localeOverrides.put('zh-rSG', 'zh-Hans-SG')`
     * set via the DSL, it must map to `zh-Hans-SG` instead -- a clear,
     * distinguishing signal that the override actually reached the task, not
     * merely that the build didn't crash.
     *
     * **Red-first, verified by mutation (not committed as a test, recorded
     * here as evidence):** commented out `task.localeOverrides.set(extension.localeOverrides)`
     * on `generateXcstrings` in `ArticulatePlugin.kt` (leaving the property
     * at its empty-map default), reran this test. Result: assertion failure
     * -- the catalog contained `"zh-SG"` (the un-overridden mapping) instead
     * of `"zh-Hans-SG"`, proving the override genuinely does nothing without
     * that wiring line. Restored the file from a backup, reran this test and
     * the full class, confirmed green again.
     */
    @Test
    fun `localeOverrides pins a directory's output locale through the plugin`() {
        writeBaseBuildFile(projectDir, extraConfig = "localeOverrides.put('zh-rSG', 'zh-Hans-SG')")
        val zhDir = File(projectDir.toFile(), "src/main/strings/values-zh-rSG").apply { mkdirs() }
        File(zhDir, "strings.xml").writeText(
            """
            <resources>
                <string name="hello">你好</string>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "generateXcstrings").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateXcstrings")!!.outcome)

        val catalogText = File(projectDir.toFile(), "ios/Shared.xcstrings").readText()
        assertTrue(
            catalogText.contains("\"zh-Hans-SG\""),
            "expected the localeOverrides-pinned locale key to appear in the generated catalog:\n$catalogText",
        )
        assertFalse(
            catalogText.contains("\"zh-SG\""),
            "the un-overridden mapping (zh-SG) must not appear -- the override must actually take effect:\n$catalogText",
        )
    }
}
