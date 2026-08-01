package net.sarazan.articulate.sample.androidApp

import android.content.Context
import net.sarazan.articulate.sample.shared.EmailError
import net.sarazan.articulate.sample.shared.EmailValidator

/**
 * PLAN.md §14 (RULED 2026-08-01): the Android half of the recommended
 * pattern -- where `:shared`'s domain outcome meets a platform that actually
 * has a localization runtime.
 *
 * [EmailValidator.validate] returns an [EmailError], never a string key, so
 * this `when` resolves each case through ordinary `R.string` constants:
 * statically visible to R8, no reflection, no `Resources.getIdentifier`, and
 * no generated lookup table. The strings themselves are authored once in
 * `:i18n`'s per-locale `strings.xml` files and reach `R.string` through
 * this module's dependency on `:i18n` (PLAN.md §4.5/§13) -- real
 * Articulate-generated resources, not hand-written ones (see [Marker]).
 *
 * The exhaustiveness is the load-bearing part. Adding a case to [EmailError]
 * breaks this `when` at compile time, on every platform consuming it, before
 * anything ships -- stronger than any key-existence check could be. An
 * earlier revision returned a raw key string and stopped at a deliberately
 * unresolved fork; §14 ruled that fork out of existence rather than
 * generating code to bridge it.
 */
object AndroidValidationDemo {

    /**
     * Not a real validation UI -- a demonstration that `:shared` and the
     * Android resource edge are both reachable from here. [email] is
     * validated by pure `commonMain` code with no Android dependency;
     * [context] resolves the resulting outcome to localized copy.
     */
    fun describe(context: Context, email: String): String {
        val greeting = context.getString(R.string.hello)
        val message = when (EmailValidator.validate(email)) {
            null -> "$email looks valid."
            EmailError.Empty -> context.getString(R.string.error_email_empty)
            EmailError.InvalidFormat -> context.getString(R.string.error_email_invalid)
        }
        return "$greeting -- $message"
    }
}
