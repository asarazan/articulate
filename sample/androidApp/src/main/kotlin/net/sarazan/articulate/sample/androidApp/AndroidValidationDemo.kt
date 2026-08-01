package net.sarazan.articulate.sample.androidApp

import android.content.Context
import net.sarazan.articulate.sample.shared.EmailValidator

/**
 * PLAN.md §14: where `:shared`'s [EmailValidator] -- naming a string with
 * nothing but a key, because `commonMain` has no localization runtime --
 * meets the Android edge, which has one but wants an **int**, not a key.
 *
 * [EmailValidator.errorKeyFor] hands back `"error_email_invalid"`, the exact
 * name of the `<string>` authored once in `:i18n/src/main/strings/values/strings.xml`.
 * Android's own `Resources.getString` signature is `getString(@StringRes id:
 * Int)` -- it does not take a string key at all. Turning
 * `"error_email_invalid"` into `R.string.error_email_invalid` from here has
 * exactly two options, neither implemented by Articulate today:
 *
 *  1. `resources.getIdentifier("error_email_invalid", "string", packageName)`
 *     -- works, but is reflective, slow, and explicitly discouraged by
 *     Android's own docs (it also defeats R8/resource shrinking's ability to
 *     see the reference statically).
 *  2. A second, generated `key -> R.string` lookup table -- which is what
 *     PLAN.md §14's still-open `commonMain` keys object would need to
 *     produce, and deliberately does not exist yet.
 *
 * This function stops exactly at that fork, on purpose: it resolves the key
 * to a *displayable message* using AGP-generated `R.string.hello` for a
 * value everyone already agrees exists (proving the Android edge really can
 * reach real, Articulate-generated resources -- see [Marker]), but does
 * **not** attempt to resolve [EmailValidator]'s key-shaped result. Making
 * that gap concrete is the sample's whole purpose (PLAN.md §14) -- filling
 * it in is explicitly out of scope here.
 */
object AndroidValidationDemo {

    /**
     * Demonstrates that the Android edge and `:shared` are both wired and
     * reachable from here -- not a real validation UI. [email] is validated
     * by [EmailValidator] (pure `commonMain` code, no Android dependency);
     * [context] is only used to prove `R.string.hello`, a real
     * Articulate-generated resource, resolves through this module's
     * dependency on `:i18n` (PLAN.md §4.5/§13).
     */
    fun describe(context: Context, email: String): String {
        val greeting = context.getString(R.string.hello)
        val errorKey = EmailValidator.errorKeyFor(email)
        return if (errorKey == null) {
            "$greeting -- $email looks valid."
        } else {
            // Deliberately NOT resolved to a displayed string -- see this
            // class's KDoc. A real app needs one of the two options listed
            // there; this sample stops at naming the problem.
            "$greeting -- validation failed, key: $errorKey (unresolved -- PLAN.md §14)"
        }
    }
}
