package net.sarazan.articulate.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * The `articulateAndroid { }` extension, read by [ArticulateAndroidPlugin].
 *
 * PLAN.md §4.2/§4.5 settle that `net.sarazan.articulate` lives on the module
 * owning the strings source tree (conventionally `:i18n`) while
 * `net.sarazan.articulate.android` lives on a *different* module (the
 * Android app module) and does only variant res wiring. [i18nProject] names
 * that module: a Gradle project path, defaulting to the `:i18n` convention
 * used elsewhere in the plan, overridable for a consumer using a different
 * module name.
 */
abstract class ArticulateAndroidExtension {

    /** Gradle project path of the module `net.sarazan.articulate` is applied to. Default `:i18n`. */
    abstract val i18nProject: Property<String>

    /**
     * Escape hatch for non-conventional layouts: the i18n-owning module's
     * strings source directory, as this app module sees it. When unset, it is
     * DERIVED by pure path convention from [i18nProject] --
     * `<rootDir>/<project path segments>/src/main/strings` -- because the IDE
     * registration must never resolve a configuration at configuration time
     * (doing so strips kotlin-stdlib from this module's Android Studio model;
     * bisection-proven 2026-08-08, see ArticulateAndroidPlugin's KDoc). A
     * build-time check verifies the derived path against what the i18n module
     * actually publishes and fails with instructions if they diverge -- so a
     * wrong convention is loud, never silent.
     */
    abstract val androidStringsDir: DirectoryProperty
}
