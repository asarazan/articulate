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
}
