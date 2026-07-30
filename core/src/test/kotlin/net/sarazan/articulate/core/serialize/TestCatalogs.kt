package net.sarazan.articulate.core.serialize

import net.sarazan.articulate.core.model.Entry
import net.sarazan.articulate.core.model.LocaleTag
import net.sarazan.articulate.core.model.Localization
import net.sarazan.articulate.core.model.PluralCategory
import net.sarazan.articulate.core.model.StringCatalog
import net.sarazan.articulate.core.model.StringKey
import net.sarazan.articulate.core.model.StringUnit

/**
 * Shared fixtures for milestone-1 serializer tests: two variants of the same
 * logical catalog, built with deliberately different map insertion order, so
 * tests can assert the serializer's own sort is what makes output identical --
 * not incidental map ordering that happened to agree.
 */
internal object TestCatalogs {

    /** app_home_title (commented, en+de) and cart_item_count (plural, en only). */
    fun sample(): StringCatalog = StringCatalog(
        sourceLanguage = "en",
        entries = linkedMapOf(
            StringKey("app_home_title") to Entry(
                comment = "Shown at the top of the home screen",
                localizations = linkedMapOf(
                    LocaleTag("en") to Localization.Simple(StringUnit("Home")),
                    LocaleTag("de") to Localization.Simple(StringUnit("Startseite")),
                ),
            ),
            StringKey("cart_item_count") to Entry(
                localizations = linkedMapOf(
                    LocaleTag("en") to Localization.Plural(
                        linkedMapOf(
                            PluralCategory.OTHER to StringUnit("%1\$lld items"),
                            PluralCategory.ONE to StringUnit("%1\$lld item"),
                        ),
                    ),
                ),
            ),
        ),
    )

    /**
     * Same logical content as [sample], every map built in reversed/shuffled
     * insertion order. If serialized output ever differs from [sample]'s, the
     * writer's sort has a bug.
     */
    fun sampleShuffled(): StringCatalog = StringCatalog(
        sourceLanguage = "en",
        entries = linkedMapOf(
            StringKey("cart_item_count") to Entry(
                localizations = linkedMapOf(
                    LocaleTag("en") to Localization.Plural(
                        linkedMapOf(
                            PluralCategory.ONE to StringUnit("%1\$lld item"),
                            PluralCategory.OTHER to StringUnit("%1\$lld items"),
                        ),
                    ),
                ),
            ),
            StringKey("app_home_title") to Entry(
                comment = "Shown at the top of the home screen",
                localizations = linkedMapOf(
                    LocaleTag("de") to Localization.Simple(StringUnit("Startseite")),
                    LocaleTag("en") to Localization.Simple(StringUnit("Home")),
                ),
            ),
        ),
    )
}
