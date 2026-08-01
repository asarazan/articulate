package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.runner
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeSettings
import net.sarazan.articulate.gradle.FunctionalTestSupport.writeValidStringsFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * IDE visibility ([ArticulatePlugin]'s `java-base` source-set registration).
 *
 * This exists because of a failure shape no other test in this repo could
 * express: a module applying only `net.sarazan.articulate` registered zero
 * source sets, so Android Studio's *Android* view -- the default -- rendered
 * `:i18n` as an empty module with no expand arrow and no `strings.xml`. Every
 * functional test drove the build through TestKit, which has no IDE model and
 * therefore could not observe it. It was found by a human opening the sample.
 *
 * TestKit still cannot see an IDE. What it *can* see is the Gradle model the
 * IDE reads, which is where the fix lives: a `strings` source set whose
 * resources point at the configured `stringsDir`. That is the checkable claim,
 * and it is what these tests check -- not "Studio renders it", which remains a
 * human verification (performed 2026-08-01, Android Studio, `sample/`).
 */
class IdeSourceSetFunctionalTest {

    /**
     * Reports the post-`afterEvaluate` source-set model. The fixture's own
     * `afterEvaluate` runs *after* the plugin's (the plugin's is registered
     * during `apply()`, from the `plugins {}` block), so this observes the
     * final state rather than racing it.
     */
    private val probe = """
        def probeLines = []
        afterEvaluate {
            def ss = project.extensions.findByName('sourceSets')
            if (ss == null) {
                probeLines << 'PROBE-NO-CONTAINER'
            } else {
                ss.each { probeLines << "PROBE-SET: ${'$'}{it.name}" }
                def strings = ss.findByName('strings')
                if (strings != null) {
                    strings.resources.srcDirs.each { probeLines << "PROBE-SRCDIR: ${'$'}{it}" }
                }
            }
        }
        tasks.register('probeSourceSets') {
            doLast { probeLines.each { println it } }
        }
    """.trimIndent()

    private fun buildFile(projectDir: Path, plugins: String, articulateBody: String = "") {
        File(projectDir.toFile(), "build.gradle").writeText(
            """
            plugins {
            $plugins
            }

            articulate {
                sourceLanguage = 'en'
                ios {
                    catalog = file('ios/Shared.xcstrings')
                }
                $articulateBody
            }

            $probe
            """.trimIndent(),
        )
    }

    @Test
    fun `plugin-only module gets a strings source set pointing at the strings tree`(@TempDir projectDir: Path) {
        writeSettings(projectDir)
        writeValidStringsFixture(projectDir)
        buildFile(projectDir, "    id 'net.sarazan.articulate'")

        val output = runner(projectDir, "probeSourceSets").build().output

        assertTrue(
            output.contains("PROBE-SET: strings"),
            "A module applying only net.sarazan.articulate must still register a source set, or the " +
                "strings tree is invisible in Android Studio's default view. Output:\n$output",
        )
        val expected = File(projectDir.toFile(), "src/main/strings").canonicalPath
        assertTrue(
            output.lines().any { it.startsWith("PROBE-SRCDIR: ") && File(it.removePrefix("PROBE-SRCDIR: ")).canonicalPath == expected },
            "The registered source set must point at the strings tree ($expected). Output:\n$output",
        )
    }

    /**
     * The distinguishing case: a hardcoded `src/main/strings` passes the test
     * above and fails this one. `stringsDir` is overridable DSL, so the IDE
     * root has to follow the extension property rather than the convention.
     */
    @Test
    fun `an overridden stringsDir moves the source set with it`(@TempDir projectDir: Path) {
        writeSettings(projectDir)
        File(projectDir.toFile(), "custom/i18n-strings/values").mkdirs()
        File(projectDir.toFile(), "custom/i18n-strings/values/strings.xml").writeText(
            "<resources><string name=\"hello\">Hello</string></resources>",
        )
        buildFile(
            projectDir,
            "    id 'net.sarazan.articulate'",
            articulateBody = "stringsDir = file('custom/i18n-strings')",
        )

        val output = runner(projectDir, "probeSourceSets").build().output

        val overridden = File(projectDir.toFile(), "custom/i18n-strings").canonicalPath
        val convention = File(projectDir.toFile(), "src/main/strings").canonicalPath
        val srcDirs = output.lines()
            .filter { it.startsWith("PROBE-SRCDIR: ") }
            .map { File(it.removePrefix("PROBE-SRCDIR: ")).canonicalPath }

        assertTrue(
            srcDirs.contains(overridden),
            "The source set must follow the overridden stringsDir ($overridden), not the convention. Output:\n$output",
        )
        assertFalse(
            srcDirs.contains(convention),
            "The unused convention path ($convention) must not be registered -- an IDE root pointing at a " +
                "directory the build ignores is worse than none. Output:\n$output",
        )
    }

    /**
     * The gate. A module that already models its sources renders correctly
     * without help and must not be handed a spare Java source set. Asserting
     * `main`/`test` are present is what makes the `strings` absence
     * meaningful -- otherwise a probe that silently saw no container at all
     * would pass this test for entirely the wrong reason.
     */
    @Test
    fun `a module that already models sources is left alone`(@TempDir projectDir: Path) {
        writeSettings(projectDir)
        writeValidStringsFixture(projectDir)
        buildFile(projectDir, "    id 'net.sarazan.articulate'\n    id 'java'")

        val output = runner(projectDir, "probeSourceSets").build().output

        assertTrue(
            output.contains("PROBE-SET: main") && output.contains("PROBE-SET: test"),
            "Guard: the probe must actually see the java plugin's source sets, otherwise the assertion " +
                "below proves nothing. Output:\n$output",
        )
        assertFalse(
            output.contains("PROBE-SET: strings"),
            "The java plugin already models sources; net.sarazan.articulate must not add a redundant " +
                "source set on top of it. Output:\n$output",
        )
    }
}
