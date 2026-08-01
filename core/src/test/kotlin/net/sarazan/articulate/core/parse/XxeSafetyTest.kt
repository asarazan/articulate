package net.sarazan.articulate.core.parse

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

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

    /**
     * [ValuesFileClassifier] (PLAN.md §4.3) opens *every other* `*.xml` in a
     * `values-<tag>` directory, with its own [javax.xml.stream.XMLInputFactory]
     * configuration -- a second reader, and therefore a second chance to get
     * this wrong. It was added after the milestone audits and had no coverage
     * here at all.
     *
     * The entity below is *internal*, deliberately: it makes the assertion
     * sensitive rather than merely plausible. If DTD processing were ever
     * re-enabled, `&inject;` would expand into a real `<string name="sneaky">`
     * element and the classifier would report *that* -- so "the message says
     * the file is malformed, and never mentions `sneaky`" fails the moment the
     * hardening is removed. (Verified by mutation: setting `SUPPORT_DTD` back
     * to `true` flips both assertions.) With DTDs refused outright, external
     * entities are unreachable by construction.
     */
    @Test
    fun `the values-file classifier refuses DTDs, so no entity can inject resources`(@TempDir dir: File) {
        val file = File(dir, "colors.xml")
        file.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE resources [
              <!ENTITY inject "<string name='sneaky'>x</string>">
            ]>
            <resources>
                <color name="c">#FFFFFF</color>
                &inject;
            </resources>
            """.trimIndent(),
        )

        val thrown = assertThrows(ConversionException::class.java) {
            ValuesFileClassifier.checkNotLocalizable(file)
        }

        val message = thrown.message.orEmpty()
        assertTrue(
            message.contains("not well-formed XML"),
            "expected the DTD to be refused outright, got: $message",
        )
        assertFalse(
            message.contains("sneaky"),
            "the entity was expanded -- DTD processing is enabled and markup can be injected: $message",
        )
    }
}
