package net.sarazan.articulate.core.model

/**
 * A translation key.
 *
 * Symbolic by mandate: `app_home_title`, never the English source string. Apple's
 * string-as-key default cannot round-trip to an Android resource name or a Kotlin
 * identifier, so keys are authored symbolically upstream.
 */
@JvmInline
value class StringKey(val value: String)

/**
 * A BCP-47 language tag as it appears in a catalog — `en`, `pt-BR`, `sr-Latn`.
 *
 * Always the *output* form. Android `values-*` qualifiers are translated into this
 * by locale mapping (milestone 3) and never reach the serializer untranslated.
 */
@JvmInline
value class LocaleTag(val value: String)

/** A CLDR plural category. Android `<plurals>` quantity names map onto these 1:1. */
enum class PluralCategory(val jsonKey: String) {
    ZERO("zero"),
    ONE("one"),
    TWO("two"),
    FEW("few"),
    MANY("many"),
    OTHER("other"),
}

/**
 * A single translated value.
 *
 * Carries no `state`. Every unit we emit is `translated`, unconditionally — the
 * catalog is a build artifact and translation state lives upstream in the source
 * XML by definition. Modelling it as a field would imply a choice that does not
 * exist, so the constant lives in [net.sarazan.articulate.core.serialize.CanonicalFormat].
 */
data class StringUnit(val value: String)

/** A key's content in one locale: either a plain value or a set of plural variations. */
sealed interface Localization {
    data class Simple(val unit: StringUnit) : Localization

    data class Plural(val variations: Map<PluralCategory, StringUnit>) : Localization
}

/**
 * One key's worth of catalog content.
 *
 * Carries no `extractionState`, for the same reason [StringUnit] carries no state:
 * it is always `manual`, which is precisely what stops Xcode marking generated
 * entries stale.
 *
 * [shouldTranslate] mirrors Android's `translatable` attribute one-for-one: the
 * string still ships in the build, it is only withheld from translation tooling
 * (verified against `xcstringstool` — see `docs/CONVERSIONS.md` K5/T-rules). `true`
 * is the default and is never emitted; the key only appears in output when `false`,
 * matching Xcode's own catalogs, which omit the field rather than writing `true`.
 */
data class Entry(
    val localizations: Map<LocaleTag, Localization>,
    val comment: String? = null,
    val shouldTranslate: Boolean = true,
)

/**
 * A whole catalog — everything serialized into one `.xcstrings` file.
 *
 * Map ordering here is deliberately unconstrained. Determinism is the serializer's
 * responsibility, not the caller's: it sorts every level on the way out, so a
 * `HashMap` and a `LinkedHashMap` with different insertion orders produce identical
 * bytes. Requiring sorted maps at construction would make the guarantee depend on
 * every call site getting it right.
 */
data class StringCatalog(
    val sourceLanguage: String,
    val entries: Map<StringKey, Entry>,
)
