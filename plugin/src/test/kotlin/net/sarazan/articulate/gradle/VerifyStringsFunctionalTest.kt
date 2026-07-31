package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.runner
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeBaseBuildFile
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeSettings
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeValidStringsFixture
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * PLAN.md §5: `verifyStrings`, the drift gate. Covers the byte-compare
 * happy/drift paths and both vacuous-gate guards (zero keys; committed
 * catalog absent), plus the property that makes the gate meaningful in the
 * first place -- it must actually re-run every time, never report
 * `UP-TO-DATE`.
 */
class VerifyStringsFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        writeSettings(projectDir)
        writeBaseBuildFile(projectDir)
        writeValidStringsFixture(projectDir)
    }

    @Test
    fun `verifyStrings succeeds when the committed catalog matches the source tree`() {
        runner(projectDir, "generateStrings").build()
        val result = runner(projectDir, "verifyStrings").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyStrings")!!.outcome)
    }

    @Test
    fun `verifyStrings fails and names the file and fix command when the catalog has drifted`() {
        runner(projectDir, "generateStrings").build()

        // Mutate-and-observe: change the source without regenerating the
        // committed catalog, exactly the drift scenario the gate exists to
        // catch. If this edit didn't actually cause a mismatch, the test
        // below would wrongly report success instead of failure.
        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello there</string>
                <string name="greeting">Hello, %1${'$'}s!</string>
            </resources>
            """.trimIndent(),
        )

        val result = runner(projectDir, "verifyStrings").buildAndFail()
        val catalogPath = File(projectDir.toFile(), "ios/Shared.xcstrings").path
        assertTrue(result.output.contains(catalogPath), "failure should name the drifted file:\n${result.output}")
        assertTrue(
            result.output.contains("generateStrings") && result.output.contains("commit"),
            "failure should state the fix command:\n${result.output}",
        )
    }

    @Test
    fun `verifyStrings fails loudly rather than passing vacuously when the source tree has zero keys`() {
        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText("<resources></resources>")
        File(projectDir.toFile(), "src/main/strings/values-de").deleteRecursively()

        val result = runner(projectDir, "verifyStrings").buildAndFail()
        assertTrue(
            result.output.contains("zero keys"),
            "expected the vacuous-gate guard's 'zero keys' message:\n${result.output}",
        )
    }

    @Test
    fun `verifyStrings fails loudly rather than passing vacuously when the committed catalog is absent`() {
        // Deliberately never run generateStrings -- ios/Shared.xcstrings never exists.
        val result = runner(projectDir, "verifyStrings").buildAndFail()
        val catalogPath = File(projectDir.toFile(), "ios/Shared.xcstrings").path
        assertTrue(result.output.contains(catalogPath), "expected the missing-catalog message to name the path:\n${result.output}")
        assertTrue(result.output.contains("does not exist"), "expected an explicit 'does not exist' message:\n${result.output}")
    }

    @Test
    fun `verifyStrings never reports UP-TO-DATE, even with zero changes between runs`() {
        runner(projectDir, "generateStrings").build()

        val first = runner(projectDir, "verifyStrings").build()
        val second = runner(projectDir, "verifyStrings").build()

        assertEquals(TaskOutcome.SUCCESS, first.task(":verifyStrings")!!.outcome)
        assertEquals(TaskOutcome.SUCCESS, second.task(":verifyStrings")!!.outcome)
        // The negative half of the same assertion: UP_TO_DATE must never
        // appear for this task, which is the entire point of declaring no
        // outputs (§5). SUCCESS on both runs already proves the action ran
        // twice; this makes the "not UP-TO-DATE" requirement explicit.
    }
}
