package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.runner
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeBaseBuildFile
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeSettings
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeValidStringsFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * PLAN.md §4.6: the configuration-cache rail. Config-cache violations are
 * silent by nature -- a build can succeed on every run while discarding and
 * rebuilding the cache every single time, and "the build succeeded" alone
 * proves nothing about that. This test asserts the actual reuse message
 * Gradle prints on a cache hit, not merely that the second build passed.
 */
class ConfigurationCacheFunctionalTest {

    @TempDir
    lateinit var projectDir: Path

    @BeforeEach
    fun setUp() {
        writeSettings(projectDir)
        writeBaseBuildFile(projectDir)
        writeValidStringsFixture(projectDir)
    }

    @Test
    fun `configuration cache is stored then reused across two identical runs`() {
        val first = runner(projectDir, "generateStrings", "--configuration-cache").build()
        assertTrue(
            first.output.contains("Configuration cache entry stored"),
            "expected the first run to store a configuration-cache entry:\n${first.output}",
        )

        val second = runner(projectDir, "generateStrings", "--configuration-cache").build()
        assertTrue(
            second.output.contains("Configuration cache entry reused."),
            "expected the second run to reuse the configuration-cache entry -- a build can succeed " +
                "while silently discarding the cache every time, which is exactly what this assertion " +
                "guards against:\n${second.output}",
        )
        // Negative half, made explicit: a discarded-and-rebuilt cache prints
        // "Configuration cache entry stored" again on the second run instead
        // of "reused" -- this is the failure mode a weaker "build succeeded"
        // assertion would miss entirely.
        assertFalse(second.output.contains("Configuration cache entry stored"))
    }

    @Test
    fun `configuration cache is reused for verifyStrings too`() {
        runner(projectDir, "generateStrings").build()

        runner(projectDir, "verifyStrings", "--configuration-cache").build()
        val second = runner(projectDir, "verifyStrings", "--configuration-cache").build()

        assertTrue(
            second.output.contains("Configuration cache entry reused."),
            "verifyStrings always re-executes (§5), but its *configuration* must still be cacheable:\n${second.output}",
        )
    }
}
