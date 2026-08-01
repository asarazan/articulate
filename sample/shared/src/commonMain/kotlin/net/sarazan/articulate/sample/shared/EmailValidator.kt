package net.sarazan.articulate.sample.shared

/**
 * PLAN.md §14: this is the whole point of `:shared` existing in this sample.
 *
 * `commonMain` has no localization runtime available to it -- it cannot call
 * Android's `getString(int)` (no `Context`, no `R` class) or iOS's
 * `String(localized:table:)` (no `Foundation` string-catalog lookup wired
 * up). So when shared validation logic needs to report *which* string a
 * caller should show, the only thing it can hand back is the raw key that
 * names a string authored once in `:i18n`'s `strings.xml` -- in this sample,
 * `error_email_invalid`.
 *
 * That is easy on iOS: `String(localized: "error_email_invalid", table:
 * "Shared")` takes a string key directly.
 *
 * It is the awkward half on Android: `Resources.getString` wants an **int**
 * resource ID (`R.string.error_email_invalid`), not the string
 * `"error_email_invalid"`. Turning this function's return value into that
 * int is exactly the unresolved question PLAN.md §14 asks a human to look
 * at -- the two ways to do it are `Resources.getIdentifier(key, "string",
 * packageName)` (reflective, and Google discourages it -- see
 * [AndroidValidationDemo] in `:androidApp`) or a second, generated
 * key -> `R.string` lookup table that Articulate does not build today.
 *
 * Deliberately not resolved here or anywhere else in this sample -- see the
 * sample's own README and PLAN.md §14's "do not implement the keys object."
 */
object EmailValidator {

    /** Returns the `:i18n` string key to show, or `null` if [email] is valid. */
    fun errorKeyFor(email: String): String? {
        val looksValid = email.contains("@") && email.substringAfterLast("@").contains(".")
        return if (looksValid) null else "error_email_invalid"
    }
}
