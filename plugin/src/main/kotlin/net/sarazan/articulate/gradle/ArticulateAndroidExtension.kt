package net.sarazan.articulate.gradle

import org.gradle.api.provider.Property

/**
 * The `articulateAndroid { }` extension, read by [ArticulateAndroidPlugin].
 *
 * PLAN.md §4.2/§4.5 settle that `net.sarazan.articulate` lives on the module
 * owning the strings source tree (conventionally `:i18n`) while
 * `net.sarazan.articulate.android` lives on a *different* module (the
 * Android app module) and does only variant res wiring -- but the plan does
 * not specify how the app module locates the i18n module's
 * `generateAndroidRes` task across that project boundary; this is a gap,
 * flagged in the milestone report rather than silently resolved. [i18nProject]
 * is this implementation's answer: a Gradle project path, defaulting to the
 * `:i18n` convention `-i18n` module names elsewhere in the plan already
 * assume, overridable for a consumer using a different module name.
 */
abstract class ArticulateAndroidExtension {

    /** Gradle project path of the module `net.sarazan.articulate` is applied to. Default `:i18n`. */
    abstract val i18nProject: Property<String>
}
