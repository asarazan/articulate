package net.sarazan.articulate.sample.shared

/**
 * PLAN.md §14 (RULED 2026-08-01): the pattern Articulate recommends for
 * shared code that needs to name a string -- and the reason Articulate
 * generates no `commonMain` keys object.
 *
 * `commonMain` has no localization runtime: no `Context` or `R` on Android,
 * no `String(localized:)` catalog lookup on iOS. The tempting move is to
 * return the raw `:i18n` key (`"error_email_invalid"`) and let each platform
 * resolve it. Don't. iOS takes a string key happily, but Android's
 * `Resources.getString` wants an **int**, which leaves the Android edge
 * choosing between `Resources.getIdentifier` (reflective, discouraged by
 * Google, and it defeats R8's ability to see the reference statically) and a
 * generated key -> `R.string` lookup table.
 *
 * **Returning a sealed type removes that fork instead of solving it.** Shared
 * code names a *domain outcome*; each platform maps that outcome to its own
 * localized string at its own edge, natively, with the compiler checking
 * exhaustiveness. Add a case here and every platform stops compiling until it
 * is handled -- which is stronger than any key-existence check. See
 * `AndroidValidationDemo` in `:androidApp` for the Android half.
 *
 * **Why Articulate does not generate this type.** Which keys form one domain
 * concept is not knowable from `strings.xml` -- that is a flat namespace, and
 * nothing in it says `error_email_empty` and `error_email_invalid` are two
 * cases of one thing while `hello` is unrelated. Inferring clusters from key
 * prefixes would be a heuristic that fails silently on someone's real
 * project. It would also mean a localization tool emitting *domain* types,
 * which is the consumer's business logic, not ours. Keeping the two catalogs
 * honest is `verifyStrings`' job; modelling your domain is yours.
 */
sealed interface EmailError {
    /** No address entered at all. */
    data object Empty : EmailError

    /** Something was entered, but it is not shaped like an address. */
    data object InvalidFormat : EmailError
}

object EmailValidator {

    /**
     * Returns the domain outcome to report, or `null` if [email] is valid.
     *
     * Note what this signature does *not* contain: any string, any key, any
     * localization dependency. That is the point.
     */
    fun validate(email: String): EmailError? {
        if (email.isBlank()) return EmailError.Empty
        val looksValid = email.contains("@") && email.substringAfterLast("@").contains(".")
        return if (looksValid) null else EmailError.InvalidFormat
    }
}
