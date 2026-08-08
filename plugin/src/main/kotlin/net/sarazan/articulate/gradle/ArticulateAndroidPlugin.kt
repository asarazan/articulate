package net.sarazan.articulate.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `net.sarazan.articulate.android` (PLAN.md §4.2/§4.5/§4.5b/§4.5c/D10).
 * Applied to the Android **app** module. Its only job is variant res wiring
 * -- registering the i18n-owning module's strings source tree
 * (conventionally `:i18n`, see [ArticulateAndroidExtension.i18nProject]) as
 * a res source directory on the app module's `main` Android source set,
 * which covers every variant without any per-variant registration.
 *
 * AGP is `compileOnly` (D9, pinned to the 9.1 floor, REVISED 2026-08-03) and
 * referenced only from this file, never from [ArticulatePlugin] or anything
 * in `:core` -- D10's entire point is that a non-Android consumer of the base
 * plugin never pulls AGP onto its classpath.
 *
 * ---
 * **Verified against a real Android build** by [AndroidWiringFunctionalTest],
 * which runs AGP 9.1 on Gradle 9.3.1 (D9's floor, REVISED 2026-08-03,
 * `compileSdk 37`) against an installed SDK and compiles a Java source
 * referencing `R.string.hello` -- proof the wired directory actually reached
 * AGP's resource merge, not merely that a task ran. Never write into a
 * variant's checked-in `res` source set.
 *
 * **This plugin never touches [ArticulateAndroidExtension.i18nProject]'s
 * project directly** -- no `project.project(path)`, no
 * `evaluationDependsOn`, no reach into `:i18n`'s task container, extensions,
 * or properties. Instead it declares a **resolvable** configuration
 * ([ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION], carrying
 * [ARTICULATE_ANDROID_RES_ATTRIBUTE]) against [ArticulatePlugin]'s matching
 * **consumable** one -- ordinary dependency-graph resolution rather than
 * project-container access, which is what survives isolated projects and
 * distinct plugin classloaders. That configuration carries the strings
 * *source* directory itself (§4.5b), not a generated copy. See PLAN.md §4.5
 * for the classloader-identity and isolated-projects failures that made the
 * original `i18nProject.tasks.named(...)` lookup release-blocking, and
 * §4.5c for the full mechanism history since.
 *
 * **Current mechanism -- THIRD AMENDMENT, 2026-08-08 (PLAN.md §4.5c):
 * registration by path convention, never by configuration-time resolution.**
 * Eagerly resolving [ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION] at
 * configuration time was proven -- by a line-level bisection through
 * published diagnostic builds -- to strip `kotlin-stdlib` from the consuming
 * module's Android Studio dependency model (CLI builds were unaffected; only
 * Studio's sync model). So the path registered via
 * `sourceSets["main"].res.directories.add(...)` at `finalizeDsl` (the
 * non-deprecated replacement for `AndroidSourceDirectorySet.srcDir(Any)`,
 * which AGP 9.1 deprecates and tracks for removal -- same payload shape, a
 * plain string derived from a `File`, never a `Provider`) is computed by
 * **pure convention** (`<rootDir>/<i18nProject path segments>/src/main/strings`),
 * with zero configuration access; `articulateAndroid { androidStringsDir }`
 * is the escape hatch for layouts that don't follow it. The payload matrix
 * that forces this shape, all empirically established (PLAN.md §4.5c):
 * `Provider` is rejected by AGP by design; `FileCollection` is resolved
 * eagerly at AGP's own apply time, before the consumer's extension block
 * even runs; only a plain `File`-derived value is safe. `verifyArticulateWiring` is
 * the one place the configuration is ever resolved -- at execution time,
 * inside a task action -- and it fails the build with a message naming the
 * fix if the convention-derived path and the i18n module's
 * actually-published directory diverge; `preBuild` depends on it, which is
 * what still runs `:i18n:validateStrings` before every variant build.
 *
 * See PLAN.md §4.5c for the full amendment history -- the abandoned AGP-8.5.2
 * `addStaticSourceDirectory` attempts, the both-plugins-one-module
 * `afterEvaluate` fix, and the SECOND AMENDMENT's Studio-editor-model
 * evidence -- this KDoc states only what currently ships.
 */
class ArticulateAndroidPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("articulateAndroid", ArticulateAndroidExtension::class.java)
        extension.i18nProject.convention(":i18n")

        project.pluginManager.withPlugin("com.android.application") {
            val androidExtension = project.extensions.getByType(ApplicationExtension::class.java)
            val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            // Resolvable side of the ArticulatePlugin.kt consumable
            // configuration. No project.project(path), no
            // evaluationDependsOn, no reach into :i18n's task container,
            // extensions, or properties -- see this class's KDoc.
            val articulateAndroidResIn = project.configurations.resolvable(
                ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION,
            ) { config ->
                config.attributes.attribute(ARTICULATE_ANDROID_RES_ATTRIBUTE, ARTICULATE_ANDROID_RES_ATTRIBUTE_VALUE)
            }.get()

            // Added as a lazy Provider, not `extension.i18nProject.get()`
            // read eagerly here: this plugin's own apply() runs before a
            // consumer's `articulateAndroid { i18nProject = ... }` block
            // does, so an eager read here would only ever observe the
            // ":i18n" convention. This also removes the ordering hazard the
            // old implementation needed `evaluationDependsOn` to defuse --
            // Gradle's own dependency resolution (not this plugin) now
            // decides when the i18n-owning project needs to be configured.
            project.dependencies.addProvider(
                articulateAndroidResIn.name,
                extension.i18nProject.map { i18nProjectPath -> project.dependencies.project(mapOf("path" to i18nProjectPath)) },
            )

            // PLAN.md §4.5c point 3: restore the validation edge explicitly.
            // preBuild depends on the verifyArticulateWiring task (registered
            // below), not on the Configuration object directly -- resolving
            // that configuration eagerly at configuration time is what the
            // THIRD AMENDMENT below proved strips kotlin-stdlib from the
            // consuming module's IDE model. verifyArticulateWiring's own
            // action is where the configuration is actually resolved (safe,
            // execution-time only), and its artifact is builtBy
            // :i18n:validateStrings, so running it carries that dependency
            // into a real build's task graph. preBuild is the one lifecycle
            // task both preDebugBuild and preReleaseBuild depend on, so this
            // covers every variant without a per-variant edge.
            project.tasks.named("preBuild").configure { preBuild ->
                preBuild.dependsOn("verifyArticulateWiring")
            }

            // THIRD AMENDMENT, 2026-08-08 (PLAN.md §4.5c) -- registration by
            // PATH CONVENTION, never by resolution. A line-level bisection
            // through published diagnostic builds proved that eagerly
            // resolving articulateAndroidResIncoming at configuration time --
            // the previous mechanism's finalizeDsl resolve -- is what strips
            // kotlin-stdlib from the consuming module's Android Studio
            // dependency model (types still infer via compiler builtins;
            // stdlib extensions like `let` stay unresolved; un-applying this
            // plugin heals it). Build A+B without the resolve: healthy.
            // Re-adding the resolve alone, with NO srcDir registration:
            // sick. The configuration and the preBuild gate are innocent.
            //
            // AGP's DSL srcDir also rejects Provider payloads by design and
            // eagerly resolves FileCollection payloads at call time (both
            // proven), so the ONLY safe payload is a plain File computed
            // WITHOUT resolution: by convention, project path ":i18n" lives
            // at <rootDir>/i18n and its strings at src/main/strings. Pure
            // string math, zero configuration access. finalizeDsl remains
            // the call window (after the consumer's extension block, DSL
            // still mutable) -- but it now touches no Configuration.
            //
            // A wrong convention is loud, not silent: verifyArticulateWiring
            // below resolves the configuration AT EXECUTION TIME (safe) and
            // fails the build if the registered path is not the directory
            // the i18n module actually publishes, naming the escape hatch.
            //
            // Registered via `res.directories.add(path)`, not `res.srcDir(path)`:
            // AndroidSourceDirectorySet.srcDir(Any) is deprecated under AGP
            // 9.1 and tracked for removal ("Use `directories` mutable set
            // instead"). Same payload requirement as above -- a plain String
            // derived from a File, never a Provider or FileCollection.
            androidComponents.finalizeDsl {
                val registered = extension.androidStringsDir.asFile.orNull
                    ?: extension.i18nProject.get().let { path ->
                        project.rootDir
                            .resolve(path.trimStart(':').replace(':', '/'))
                            .resolve("src/main/strings")
                    }
                androidExtension.sourceSets.getByName("main").res.directories.add(registered.absolutePath)
            }

            // Execution-time wiring check: the one place the configuration is
            // ever resolved, safely inside a task action. Carries the
            // validateStrings gate implicitly (the configuration's artifact
            // is builtBy validateStrings), so this task REPLACES the bare
            // preBuild.dependsOn(configuration) edge as the gate carrier --
            // preBuild depends on this task instead (wired above).
            // Everything the task action needs is captured as serializable
            // values up front -- a live Configuration or Project reference in
            // doLast breaks configuration-cache reuse (caught by the CC
            // tests on first run of this mechanism).
            val incomingFiles = project.files(articulateAndroidResIn)
            val rootDir = project.rootDir
            val registeredProvider = extension.androidStringsDir.asFile
                .orElse(extension.i18nProject.map { path ->
                    rootDir
                        .resolve(path.trimStart(':').replace(':', '/'))
                        .resolve("src/main/strings")
                })
            project.tasks.register("verifyArticulateWiring") { task ->
                task.group = ARTICULATE_TASK_GROUP
                task.description = "Fails if the convention-derived res registration does not match the " +
                    "directory the i18n-owning module actually publishes (articulateAndroidResElements)."
                task.inputs.files(incomingFiles).withPropertyName("articulateAndroidResIncoming")
                task.doLast {
                    val published = incomingFiles.files.singleOrNull() ?: throw GradleException(
                        "net.sarazan.articulate.android: resolving " +
                            "$ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION produced " +
                            "${incomingFiles.files.size} files (expected exactly 1 -- the i18n-owning " +
                            "project's strings source directory). Check that the project named by " +
                            "articulateAndroid { i18nProject } applies net.sarazan.articulate.",
                    )
                    val registered = registeredProvider.get()
                    if (registered.canonicalFile != published.canonicalFile) {
                        throw GradleException(
                            "net.sarazan.articulate.android: the res directory registered for the IDE " +
                                "(${registered}) is not the directory the i18n module publishes " +
                                "(${published}). Fix: set articulateAndroid { androidStringsDir = " +
                                "file(\"${published}\") } in this module, or align the i18n module's " +
                                "articulate { stringsDir } with the src/main/strings convention.",
                        )
                    }
                }
            }
        }
    }
}
