package net.sarazan.articulate.core.corpus

import net.sarazan.articulate.core.convert.AndroidToXcstringsConverter
import net.sarazan.articulate.core.diagnostics.Diagnostic
import net.sarazan.articulate.core.serialize.XcstringsWriter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.stream.Stream

/**
 * Directory-per-case golden tests, per PLAN.md §2.1. Each subdirectory of
 * `core/src/test/corpus/` is one dynamic test, discovered automatically --
 * adding a case is adding files, never editing this runner.
 *
 * A case is either:
 *  - **success**: `input/values[-<tag>]/strings.xml` + `expected.xcstrings`,
 *    a byte-exact expectation, plus an *optional* `expected-warnings.txt`
 *    (PLAN.md §2.7) -- one required substring per non-blank line that some
 *    emitted [Diagnostic] must contain. A case with no such file must produce
 *    **zero** diagnostics, so an unwritten warning can never sneak in
 *    unnoticed.
 *  - **error**: `input/values[-<tag>]/strings.xml` + `expected-error.txt`,
 *    one required substring per non-blank line that the thrown exception's
 *    message must contain (name-the-file/key/fix quality, not just "it failed").
 *
 * Every `expected.xcstrings` here is generated from this project's own
 * converter + [XcstringsWriter], never hand-typed (see the milestone's own
 * warning about transcribing nested JSON by hand) -- which also means this
 * test's core assertion *is* the corpus's self-consistency check: the
 * checked-in file is byte-identical to what the canonical serializer
 * currently produces, by construction.
 */
class CorpusTest {

    @TestFactory
    fun corpus(): Stream<DynamicTest> {
        val root = corpusRoot()
        val cases = root.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        check(cases.isNotEmpty()) { "no corpus cases found under ${root.path}" }
        return cases.stream().map { dir -> DynamicTest.dynamicTest(dir.name) { runCase(dir) } }
    }

    private fun runCase(dir: File) {
        val inputDir = File(dir, "input")
        check(inputDir.isDirectory) { "corpus case '${dir.name}' is missing an input/ directory" }
        val expectedXcstrings = File(dir, "expected.xcstrings")
        val expectedError = File(dir, "expected-error.txt")

        when {
            expectedXcstrings.isFile -> runSuccessCase(dir.name, inputDir, expectedXcstrings)
            expectedError.isFile -> runErrorCase(dir.name, inputDir, expectedError)
            else -> fail<Unit>("corpus case '${dir.name}' has neither expected.xcstrings nor expected-error.txt")
        }
    }

    private fun runSuccessCase(name: String, inputDir: File, expectedFile: File) {
        val result = try {
            AndroidToXcstringsConverter.convert(inputDir)
        } catch (e: Exception) {
            fail<Unit>("corpus case '$name' expected success but conversion threw: ${e.message}", e)
            return
        }
        val actual = XcstringsWriter.writeBytes(result.catalog)
        val expected = expectedFile.readBytes()
        assertArrayEquals(expected, actual) { "corpus case '$name': serialized catalog does not byte-match expected.xcstrings" }

        runWarningsAssertion(name, expectedFile.parentFile, result.diagnostics)
    }

    private fun runWarningsAssertion(name: String, caseDir: File, diagnostics: List<Diagnostic>) {
        val expectedWarnings = File(caseDir, "expected-warnings.txt")
        if (!expectedWarnings.isFile) {
            assertTrue(diagnostics.isEmpty()) {
                "corpus case '$name': expected zero warnings (no expected-warnings.txt present) but " +
                    "got ${diagnostics.size}:\n${diagnostics.joinToString("\n")}"
            }
            return
        }

        val requiredSubstrings = expectedWarnings.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        check(requiredSubstrings.isNotEmpty()) { "corpus case '$name': expected-warnings.txt has no content" }
        checkSubstringsCanFail(name, caseDir.path, requiredSubstrings)

        val rendered = diagnostics.map { it.toString() }
        for (required in requiredSubstrings) {
            assertTrue(rendered.any { it.contains(required) }) {
                "corpus case '$name': no warning contained expected substring '$required'.\n" +
                    "Actual warnings:\n${rendered.joinToString("\n").ifEmpty { "(none)" }}"
            }
        }
    }

    private fun runErrorCase(name: String, inputDir: File, expectedFile: File) {
        val requiredSubstrings = expectedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        check(requiredSubstrings.isNotEmpty()) { "corpus case '$name': expected-error.txt has no content" }
        checkSubstringsCanFail(name, expectedFile.parentFile.path, requiredSubstrings)

        val message = try {
            AndroidToXcstringsConverter.convert(inputDir)
            null
        } catch (e: Exception) {
            e.message
        }

        assertTrue(message != null) { "corpus case '$name' expected an error but conversion succeeded" }
        for (required in requiredSubstrings) {
            assertTrue(message!!.contains(required)) {
                "corpus case '$name': error message did not contain expected substring '$required'.\nActual message: $message"
            }
        }

        // PLAN.md §2.1: "messages must name the file, the key, and the fix". The
        // file half is universal, so it is enforced here for every error case rather
        // than left to each expected-error.txt to remember. Matched by extension, not
        // literally "strings.xml": §4.3's multi-file classifier can fail on a
        // companion file (plurals.xml, or a content file misnamed marketing.xml)
        // that is never named strings.xml at all.
        val inputFiles = inputDir.walkTopDown().filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }.map { it.path }.toList()
        check(inputFiles.isNotEmpty()) { "corpus case '$name': no input XML files found" }
        assertTrue(inputFiles.any { message!!.contains(it) }) {
            "corpus case '$name': error message names none of the input XML files $inputFiles -- " +
                "PLAN.md §2.1 requires every failure to name the file.\nActual message: $message"
        }
    }

    /**
     * Guard against an assertion that cannot fail. Every message/diagnostic
     * embeds the offending file's path, and that path contains the case's
     * own directory name -- so a required substring drawn from the case name
     * (`precision` in `error-string-precision`, `entities` in `xml-entities`)
     * is satisfied by the path alone and tests nothing at all. Two error
     * cases shipped exactly this bug before this check existed; it applies
     * equally to `expected-warnings.txt`.
     */
    private fun checkSubstringsCanFail(name: String, casePath: String, requiredSubstrings: List<String>) {
        for (required in requiredSubstrings) {
            check(!casePath.contains(required)) {
                "corpus case '$name': required substring '$required' also occurs in the case's own " +
                    "path ('$casePath'), which every message quotes -- the assertion can never fail. " +
                    "Assert something from the message body instead."
            }
        }
    }
}

private fun corpusRoot(): File {
    val candidates = listOf(File("src/test/corpus"), File("core/src/test/corpus"))
    for (c in candidates) if (c.isDirectory) return c.canonicalFile
    error("could not locate src/test/corpus from working directory ${File(".").canonicalPath}")
}
