package net.sarazan.articulate.gradle

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

    @Test
    fun `net sarazan articulate builds generateStrings successfully under the Gradle 8 7 floor`() {
        writeSettings(projectDir)
        writeBaseBuildFile(projectDir)
        writeValidStringsFixture(projectDir)

        val result = GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withGradleVersion("8.7")
            .withArguments("generateStrings")
            .forwardOutput()
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":generateStrings")!!.outcome)
        assertTrue(File(projectDir.toFile(), "ios/Shared.xcstrings").isFile)
    }
}
