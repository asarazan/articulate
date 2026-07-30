package net.sarazan.articulate.core.serialize

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * Locks the exact canonical output for one representative catalog against a
 * checked-in fixture file (`resources/golden/sample.xcstrings`), so a reviewer
 * can read the expectation directly rather than through test code, and a diff
 * against it in review is the same kind of diff `verifyStrings` will produce
 * in milestone 5.
 *
 * This is a regression lock, not independent verification against real Xcode --
 * that is [RoundTripTest]'s job once the observed-Xcode fixture (see
 * `fixtures/xcode/README.md`) lands. Until then "golden" means "what
 * [CanonicalFormat] currently, provisionally, produces" -- what it locks down is
 * sort order (alphabetical at every level, including `de` before `en` despite
 * being inserted in the opposite order in [TestCatalogs.sample]), comma
 * placement, comment omission when absent, and the plural wrapper shape --
 * not the still-provisional formatting constants themselves.
 *
 * The fixture file was captured from this writer's own already-verified output
 * (see git history for `GoldenFixtureGenerator`, since deleted), not hand-typed
 * -- transcribing deeply nested indentation by hand is exactly the kind of
 * mistake this test exists to catch, not commit.
 */
class GoldenSerializationTest {

    @Test
    fun `sample catalog serializes to the checked-in golden fixture`() {
        val expected = requireNotNull(
            javaClass.getResourceAsStream("/golden/sample.xcstrings"),
        ) { "Missing test resource: golden/sample.xcstrings" }.use { it.readBytes() }

        assertArrayEquals(expected, XcstringsWriter.writeBytes(TestCatalogs.sample()))
    }
}
