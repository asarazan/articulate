package net.sarazan.articulate.gradle

import org.gradle.api.attributes.Attribute

/**
 * PLAN.md §4.5/§4.5b/§13: how `net.sarazan.articulate.android` (applied to
 * the app module) obtains `net.sarazan.articulate`'s (applied to the
 * i18n-owning module, conventionally `:i18n`) strings source tree,
 * replacing the release-blocking cross-project task-container lookup
 * (`i18nProject.tasks.named("generateAndroidRes", GenerateAndroidResTask::class.java)`).
 *
 * The i18n-owning project exposes a **consumable** configuration carrying
 * [ARTICULATE_ANDROID_RES_ATTRIBUTE] and publishing the strings *source*
 * directory itself (`extension.stringsDir`, artifact `builtBy validateStrings`
 * -- PLAN.md §4.5b), not a generated copy. The app module declares a
 * **resolvable** configuration with the same attribute plus an ordinary
 * project dependency, then resolves it. That is dependency-graph resolution,
 * not project-container access -- permitted under isolated projects
 * (verified by prototype, PLAN.md §4.5), and it carries the implicit
 * dependency on `:i18n:validateStrings` via the artifact's `builtBy`, with no
 * `dependsOn` on a project anywhere in this plugin.
 *
 * **Both [ArticulatePlugin] and [ArticulateAndroidPlugin] reference this same
 * file, and that is not a class-identity requirement across classloaders.**
 * The two plugin instances typically run in *different* plugin classloaders
 * (the same-class-different-classloader hazard this whole redesign exists to
 * avoid) -- but nothing here is ever compared by object identity across that
 * boundary. [Attribute.of] returns an object whose equality (used internally
 * by Gradle's variant-aware attribute matching) is defined by [Attribute.getName]
 * (a plain `String`) and [Attribute.getType] (`String::class.java` here,
 * always bootstrap-loaded and therefore identical in every classloader).
 * Each project's own plugin instance calls [Attribute.of] independently, at
 * configuration time, inside its own project; Gradle's resolution engine --
 * not this code -- is what compares the two resulting attribute values
 * across the project boundary, and it does so by value, not by our object's
 * identity. No `GenerateAndroidResTask` type, no method reference, and no
 * other Articulate class ever crosses the boundary.
 */
internal val ARTICULATE_ANDROID_RES_ATTRIBUTE: Attribute<String> =
    Attribute.of("net.sarazan.articulate.android-res", String::class.java)

/** The one value [ARTICULATE_ANDROID_RES_ATTRIBUTE] ever takes. */
internal const val ARTICULATE_ANDROID_RES_ATTRIBUTE_VALUE = "android-res-dir"

/** Name of the consumable configuration [ArticulatePlugin] creates on the i18n-owning project. */
internal const val ARTICULATE_ANDROID_RES_ELEMENTS_CONFIGURATION = "articulateAndroidResElements"

/** Name of the resolvable configuration [ArticulateAndroidPlugin] creates on the app project. */
internal const val ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION = "articulateAndroidResIncoming"
