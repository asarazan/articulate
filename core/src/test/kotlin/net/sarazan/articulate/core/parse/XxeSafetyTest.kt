package net.sarazan.articulate.core.parse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * The parser must never resolve a DTD-declared external entity: a malicious
 * `strings.xml` could otherwise exfiltrate local file contents into the
 * generated catalog. This is a security property, not a behavioral one, so it
 * gets a dedicated test rather than relying on a corpus case to notice.
 */
class XxeSafetyTest {

    @Test
    fun `external entity referencing a local file is never resolved`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE resources [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <resources>
                <string name="a">&xxe;</string>
            </resources>
        """.trimIndent()

        // DTDs are disabled outright, so this must fail to parse -- it must not
        // succeed with /etc/passwd's contents spliced into the value.
        val thrown = assertThrows(Exception::class.java) {
            AndroidStringsParser.parse(ByteArrayInputStream(xml.toByteArray()), "test.xml")
        }
        assertFalse(thrown.message.orEmpty().contains("root:"))
    }
}
