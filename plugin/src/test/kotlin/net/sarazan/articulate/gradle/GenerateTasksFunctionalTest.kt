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
 * behavior for `generateAndroidRes`, `generateXcstrings`, and the
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
    fun `generateStrings aggregate invokes both generate tasks and produces both outputs`() {
        val result = runner(projectDir, "generateStrings").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateAndroidRes")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateXcstrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStrings")!!.outcome)

        val catalog = File(projectDir.toFile(), "ios/Shared.xcstrings")
        assertTrue(catalog.isFile, "expected ios/Shared.xcstrings to be written")
        val catalogText = catalog.readText()
        assertTrue(catalogText.contains("\"hello\""), "catalog should contain the 'hello' key:\n$catalogText")
        assertTrue(catalogText.contains("\"sourceLanguage\" : \"en\""), "catalog should declare sourceLanguage en:\n$catalogText")

        val androidValues = File(projectDir.toFile(), "build/generated/i18n/res/values/strings.xml")
        val androidValuesDe = File(projectDir.toFile(), "build/generated/i18n/res/values-de/strings.xml")
        assertTrue(androidValues.isFile, "expected generated Android res values/strings.xml")
        assertTrue(androidValuesDe.isFile, "expected generated Android res values-de/strings.xml")
        assertTrue(androidValues.readText().contains("hello"))
    }

    @Test
    fun `second run with no source change reports both generate tasks UP-TO-DATE`() {
        runner(projectDir, "generateStrings").build()
        val second = runner(projectDir, "generateStrings").build()

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateAndroidRes")!!.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":generateXcstrings")!!.outcome)
    }

    @Test
    fun `editing one locale's strings xml re-runs both generate tasks`() {
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
        assertEquals(TaskOutcome.SUCCESS, second.task(":generateAndroidRes")!!.outcome)
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
     * Task 4 (PLAN.md §13's M4/M5 audit): D4 ships the `markupPolicy` knob in
     * v0 with only `ERROR` implemented -- setting `STRIP`/`VERBATIM` used to
     * silently behave as `ERROR` anyway, with no diagnostic. This asserts the
     * fail-fast replacement: the build fails at configuration time (before
     * any task executes) and the message names the value, that only `ERROR`
     * is supported, and PLAN.md §2.2/D4.
     */
    @Test
    fun `markupPolicy set to STRIP fails fast at configuration time`() {
        writeBaseBuildFile(
            projectDir,
            extraConfig = "markupPolicy = net.sarazan.articulate.core.convert.MarkupPolicy.STRIP",
        )

        val result = runner(projectDir, "help").buildAndFail()

        assertTrue(
            result.output.contains("markupPolicy") &&
                result.output.contains("STRIP") &&
                result.output.contains("not yet implemented") &&
                result.output.contains("MarkupPolicy.ERROR") &&
                result.output.contains("D4"),
            "expected a fail-fast message naming the value, ERROR as the only supported one, and D4:\n${result.output}",
        )
        // No task should have even started -- this must fail during
        // configuration, not merely as a side effect of some task's action.
        assertFalse(result.output.contains("BUILD SUCCESSFUL"))
    }

    /**
     * VERBATIM must fail the same way as STRIP -- both are unimplemented,
     * and this test exists specifically so "only STRIP is special-cased"
     * cannot creep in unnoticed (mutate-and-observe, performed by hand:
     * hardcoding the check to `policy == MarkupPolicy.STRIP` instead of
     * `!= MarkupPolicy.ERROR` passes the test above but fails this one).
     */
    @Test
    fun `markupPolicy set to VERBATIM also fails fast`() {
        writeBaseBuildFile(
            projectDir,
            extraConfig = "markupPolicy = net.sarazan.articulate.core.convert.MarkupPolicy.VERBATIM",
        )

        val result = runner(projectDir, "help").buildAndFail()

        assertTrue(
            result.output.contains("VERBATIM") && result.output.contains("not yet implemented"),
            "expected VERBATIM to fail fast exactly like STRIP:\n${result.output}",
        )
    }

    /**
     * The default (`ERROR`, unset) must keep working -- this is the
     * companion negative case proving the two tests above are actually
     * caused by the non-default value, not by `writeBaseBuildFile`'s
     * `extraConfig` mechanism itself or some other regression.
     */
    @Test
    fun `markupPolicy left at its default ERROR does not fail fast`() {
        val result = runner(projectDir, "help").build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
    }
}
