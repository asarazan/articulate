package net.sarazan.articulate.core.serialize

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * The definitional test of canonical form: parse Xcode's own output,
 * re-serialize it, and the result must be byte-identical to what Xcode wrote.
 *
 * Disabled until the observed-Xcode fixture lands -- see
 * `core/src/test/fixtures/xcode/README.md`. Un-disabling this is also the
 * trigger to implement `XcstringsReader`, which does not exist yet: nothing
 * before this test needs it, and writing it against guessed format details
 * would be exactly the kind of unverified assumption this project is
 * structured to avoid.
 */
@Disabled("pending Xcode fixture -- see core/src/test/fixtures/xcode/README.md")
class RoundTripTest {

    @Test
    fun `serializing a parsed Xcode-produced catalog reproduces it byte-for-byte`() {
        TODO("Implement once fixtures/xcode/opened.xcstrings exists and XcstringsReader is written.")
    }
}
