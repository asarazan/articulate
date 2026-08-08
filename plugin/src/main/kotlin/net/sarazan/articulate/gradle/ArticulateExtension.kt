package net.sarazan.articulate.gradle

import net.sarazan.articulate.core.convert.MarkupPolicy
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `articulate { }` extension (PLAN.md §E4/D10). Applied by
 * [ArticulatePlugin] to whichever module owns the strings source tree
 * (conventionally `:i18n`).
 *
 * Conventions (`stringsDir` = `src/main/strings`, `sourceLanguage` = `"en"`,
 * `markupPolicy` = [MarkupPolicy.ERROR], `warningsAsErrors` = `false`) are
 * set by [ArticulatePlugin.apply], not here, so this class stays a plain
 * property bag with no `Project`/`ProjectLayout` dependency of its own.
 */
abstract class ArticulateExtension @Inject constructor(objects: ObjectFactory) {

    /** Root of the strings source tree; `values/`, `values-de/`, ... live directly under it. */
    abstract val stringsDir: DirectoryProperty

    /** BCP-47/Android language code for the default `values/` directory. */
    abstract val sourceLanguage: Property<String>

    /**
     * PLAN.md §3.1's escape hatch: pins a directory qualifier (everything
     * after `values-`, or `""` for bare `values`) to an explicit output
     * locale tag, overriding whatever [net.sarazan.articulate.core.locale.AndroidLocaleMapper]
     * would otherwise compute -- including its non-locale-qualifier rejection.
     */
    abstract val localeOverrides: MapProperty<String, String>

    /**
     * D4's escape hatch. Declared here so the DSL shape a consumer builds
     * against doesn't change when `STRIP`/`VERBATIM` are implemented, but it
     * is not yet wired to [net.sarazan.articulate.core.convert.AndroidToXcstringsConverter.convert]
     * -- that function has no such parameter today (core's own KDoc: "there
     * is deliberately no parameter wiring this in yet, since adding one
     * before a second policy exists would be speculative API surface with no
     * caller"). Setting anything other than the default [MarkupPolicy.ERROR]
     * fails the build fast at configuration time (see [ArticulatePlugin.apply])
     * rather than silently behaving as `ERROR` anyway -- a user who asks for
     * `STRIP` and silently gets `ERROR` is exactly the "95% right is worse
     * than none" hazard the brief forbids.
     */
    abstract val markupPolicy: Property<MarkupPolicy>

    /**
     * PLAN.md §E4/§4.7: escalates every diagnostic from §2.7's channel
     * (foreign-namespace tags, irregular key names) from a logged `WARN` to a
     * failed build. Defaults to `false` -- advisory by default, opt-in to
     * strict.
     */
    abstract val warningsAsErrors: Property<Boolean>

    val ios: IosExtension = objects.newInstance(IosExtension::class.java)

    fun ios(action: Action<IosExtension>) {
        action.execute(ios)
    }
}

/** The `articulate { ios { ... } }` nested block. */
abstract class IosExtension {

    /** Path of the committed `.xcstrings` catalog `generateXcstrings`/`verifyStrings` target. */
    abstract val catalog: RegularFileProperty
}
