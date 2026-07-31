package net.sarazan.articulate.core.serialize

import net.sarazan.articulate.core.model.Entry
import net.sarazan.articulate.core.model.Localization
import net.sarazan.articulate.core.model.StringCatalog
import net.sarazan.articulate.core.model.StringUnit

/**
 * Serializes a [StringCatalog] to canonical `.xcstrings` text.
 *
 * Deliberately hand-rolled rather than built on a JSON library: the output must
 * match Xcode's own formatting byte-for-byte (notably its spaced `"key" : value`
 * separator), which no standard pretty-printer produces. See [CanonicalFormat].
 *
 * Output is a pure function of the model. No timestamps, no environment, no
 * dependence on map insertion order or the machine's default locale.
 */
object XcstringsWriter {

    /** Canonical text for [catalog]. */
    fun write(catalog: StringCatalog): String = catalog.toJson().renderCanonical()

    /** Canonical bytes for [catalog] — what gets committed to disk. */
    fun writeBytes(catalog: StringCatalog): ByteArray = write(catalog).toByteArray(CanonicalFormat.CHARSET)
}

private fun StringCatalog.toJson(): JsonValue.Obj = JsonValue.Obj(
    mapOf(
        "sourceLanguage" to JsonValue.Str(sourceLanguage),
        "strings" to JsonValue.Obj(entries.entries.associate { (key, entry) -> key.value to entry.toJson() }),
        "version" to JsonValue.Str(CanonicalFormat.VERSION),
    ),
)

private fun Entry.toJson(): JsonValue.Obj = JsonValue.Obj(
    buildMap {
        // Omitted entirely when absent — an empty comment is not the same as no comment.
        comment?.let { put("comment", JsonValue.Str(it)) }
        put("extractionState", JsonValue.Str(CanonicalFormat.EXTRACTION_STATE))
        put(
            "localizations",
            JsonValue.Obj(localizations.entries.associate { (tag, localization) -> tag.value to localization.toJson() }),
        )
        // Matches Xcode's own catalogs: `true` is the default and is never written,
        // only `false` appears (see Entry.shouldTranslate KDoc).
        if (!shouldTranslate) put("shouldTranslate", JsonValue.Bool(false))
    },
)

private fun Localization.toJson(): JsonValue.Obj = when (this) {
    is Localization.Simple -> JsonValue.Obj(mapOf("stringUnit" to unit.toJson()))
    is Localization.Plural -> JsonValue.Obj(
        mapOf(
            "variations" to JsonValue.Obj(
                mapOf(
                    "plural" to JsonValue.Obj(
                        variations.entries.associate { (category, unit) ->
                            category.jsonKey to JsonValue.Obj(mapOf("stringUnit" to unit.toJson()))
                        },
                    ),
                ),
            ),
        ),
    )
}

private fun StringUnit.toJson(): JsonValue.Obj = JsonValue.Obj(
    mapOf(
        "state" to JsonValue.Str(CanonicalFormat.STATE),
        "value" to JsonValue.Str(value),
    ),
)
