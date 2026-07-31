package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.GRADLE_FLOOR_VERSION
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeBaseBuildFile
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeSettings
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeValidStringsFixture
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * PLAN.md §E2/D9: the supported floor is Gradle 8.7. `gradleApi()` binds to
 * the *building* Gradle (9.5.0 here) at compile time, so nothing at compile
 * time stops `plugin` from calling a Gradle 9-only API that a floor consumer
 * running actual Gradle 8.7 would hit as a `NoSuchMethodError` at runtime,
 * not at our build time. `GradleRunner.withGradleVersion("8.7")` is the
 * mechanism §D9 names to catch exactly that class of mistake -- it runs the
 * plugin's own compiled classes against a real Gradle 8.7 distribution.
 *
 * Deliberately does not apply `net.sarazan.articulate.android` or any AGP:
 * this exercises only the `:i18n`-side plugin, which is the half of D10's
 * split that carries no AGP and is fully testable without an SDK.
 */
class GradleFloorFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    /**
     * Reads [FunctionalTestSupport.GRADLE_FLOOR_VERSION] rather than repeating
     * the literal `"8.7"`, so the floor lives in exactly one place inside this
     * source set and a D9 revision cannot update some call sites but not
     * others.
     */
    private fun floorRunner(vararg args: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withGradleVersion(GRADLE_FLOOR_VERSION)
            .withArguments(*args)
            .forwardOutput()

    @Test
    fun `net sarazan articulate builds generateStrings successfully under the Gradle 8 7 floor`() {
        writeSettings(projectDir)
        writeBaseBuildFile(projectDir)
        writeValidStringsFixture(projectDir)

        val result = floorRunner("generateStrings").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStrings")!!.outcome)
        assertTrue(File(projectDir.toFile(), "ios/Shared.xcstrings").isFile)
    }

    /**
     * Milestone 5's entire deliverable, exercised under the floor too.
     * `generateStrings` alone leaves [net.sarazan.articulate.gradle.tasks.VerifyStringsTask]'s
     * action never executed under Gradle 8.7 -- a Gradle-9-only API reachable
     * only from the gate would have compiled here and failed at a floor
     * consumer's runtime, which is the exact failure class this class exists
     * to catch. Both outcomes are asserted: the pass path *and* the drift
     * failure, since a gate that only ever succeeds proves nothing.
     */
    @Test
    fun `verifyStrings both passes and catches drift under the Gradle 8 7 floor`() {
        writeSettings(projectDir)
        writeBaseBuildFile(projectDir)
        writeValidStringsFixture(projectDir)

        floorRunner("generateStrings").build()
        assertEquals(TaskOutcome.SUCCESS, floorRunner("verifyStrings").build().task(":verifyStrings")!!.outcome)

        File(projectDir.toFile(), "src/main/strings/values/strings.xml").writeText(
            """
            <resources>
                <string name="hello">Hello there</string>
                <string name="greeting">Hello, %1${'$'}s!</string>
            </resources>
            """.trimIndent(),
        )

        val drifted = floorRunner("verifyStrings").buildAndFail()
        assertTrue(
            drifted.output.contains("is out of date with the strings source tree") &&
                drifted.output.contains("generateStrings"),
            "the drift gate must fail with its own message under the floor Gradle:\n${drifted.output}",
        )
    }
}
