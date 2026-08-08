package net.sarazan.articulate.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `net.sarazan.articulate.android` (PLAN.md §4.2/§4.5/§4.5c/D10). Applied to
 * the Android **app** module. Its only job is variant res wiring --
 * registering the i18n-owning module's strings source tree (conventionally
 * `:i18n`, see [ArticulateAndroidExtension.i18nProject]) as a res source
 * directory on the app module's `main` Android source set, which covers
 * every variant without any per-variant registration. **As of the SECOND
 * AMENDMENT, 2026-08-06 (PLAN.md §4.5c)** this is a literal-path DSL
 * `sourceSets["main"].res.srcDir(...)` call made from `finalizeDsl` -- see
 * the dated section near the end of this KDoc for why, and for the two
 * mechanisms it replaces, kept above as history.
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
 * variant's checked-in `res` source set -- that rule still holds. **The
 * companion rule directly below, "never use the legacy
 * `sourceSets["main"].res.srcDir` (both settled, §4.5)", is OVERTURNED as of
 * 2026-08-06** -- kept here rather than deleted, because it was a real,
 * reasoned ruling at the time and the record should show it was reversed,
 * not silently disappear. It is reversed because the Variant API alternative
 * that motivated it (`addStaticSourceDirectory`) was subsequently proven
 * blind to Android Studio's editor model, while the legacy DSL `srcDir` call
 * was proven to reach it. See the "SECOND AMENDMENT, 2026-08-06" section
 * near the end of this KDoc for the evidence.
 *
 * **Cross-project mechanism, redesigned 2026-08-01 (PLAN.md §4.5/§13,
 * release-blocking).** The previous implementation resolved the producing
 * task by reaching directly into the i18n-owning project's task container
 * (`i18nProject.tasks.named("generateAndroidRes", GenerateAndroidResTask::class.java)`),
 * which (a) compared `GenerateAndroidResTask`'s `Class` identity across two
 * *different* plugin classloaders whenever a consumer applied the two plugin
 * IDs from separate module `plugins {}` blocks -- the natural, Android
 * Studio-default layout -- throwing `InvalidUserDataException: ... is not a
 * subclass of the given type` even though it is the same class by name, and
 * (b) was rejected outright under `-Dorg.gradle.unsafe.isolated-projects=true`
 * (`Cannot access project ':i18n' from project ':app'`).
 *
 * This plugin now never touches [ArticulateAndroidExtension.i18nProject]'s
 * project directly. Instead it declares a **resolvable** configuration
 * ([ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION]) carrying
 * [ARTICULATE_ANDROID_RES_ATTRIBUTE], with an ordinary project dependency on
 * the i18n-owning project -- resolved against [ArticulatePlugin]'s matching
 * **consumable** configuration. That is dependency-graph resolution, not
 * project-container access: permitted under isolated projects, with no
 * `evaluationDependsOn` or `dependsOn` on a *project* anywhere in this file.
 * No `ValidateStringsTask` type, and no method reference against it, ever
 * crosses the classloader boundary.
 *
 * **§4.5b changed what this configuration carries.** `:i18n`'s consumable
 * configuration publishes the strings *source* directory itself
 * (`extension.stringsDir`), not a task's generated output (PLAN.md §4.5b
 * point 1) -- because that source tree is already valid Android resource
 * layout (`GenerateAndroidResTask` was verified to be `copyTo`, nothing more,
 * before it was deleted).
 *
 * **§4.5c (SHIPPED 2026-08-05): static registration, no copy, gate restored
 * explicitly.** Earlier revisions of this plugin (kept below as history)
 * materialized the resolved configuration into an app-owned copy via
 * `ResolveArticulateAndroidResTask` + `addGeneratedSourceDirectory`, because
 * `addStaticSourceDirectory` was prototyped and found to be a dead end under
 * AGP 8.5.2 (see "abandoned attempt" below). **The AGP 9.1.0 floor (§E2/D9,
 * REVISED 2026-08-03) changes that outcome**: §4.5c's own probe, run with a
 * control, showed `variant.sources.res?.addStaticSourceDirectory(path)`
 * alone -- fed a path resolved eagerly from inside `onVariants` -- compiles
 * `R.string.hello` from `:i18n`'s real *source* tree on AGP 9.1, on both
 * debug and release, where the identical code dies at configuration time
 * under AGP 8.5.2.
 *
 * This is a strictly better fix for the open IDE-visibility defect (PLAN.md
 * §4.5's "IDE visibility of generated Android res"): the registered path is
 * `:i18n`'s always-on-disk source tree, not a generated copy that only
 * exists after a build runs, so a sync that merely reads the variant model
 * (never executing tasks) can resolve `R.string.your_key` with nothing built
 * -- pending human confirmation in Studio, see PLAN.md §4.5/§4.5c.
 *
 * `ResolveArticulateAndroidResTask` and the `addGeneratedSourceDirectory`
 * registration are deleted outright -- no copy of :i18n's strings tree
 * exists anywhere any more, and only one registration mechanism
 * (`addStaticSourceDirectory`) is ever active.
 *
 * **§4.5c correction, found implementing it (2026-08-05): resolution cannot
 * happen synchronously inside `onVariants` after all -- not universally.**
 * §4.5c's own text says to resolve "inside `onVariants` (the proven window)"
 * and call `addStaticSourceDirectory` there directly. That works for the
 * ordinary cross-project layout (`:i18n` + `:app`, verified by
 * [AndroidWiringFunctionalTest]'s compile-based `R.string.hello` proof), but
 * it breaks a case §4.5c's probe never covered: **both plugin IDs applied to
 * one module** (`articulateAndroid { i18nProject = ":app" }`, i.e. this
 * project depending on its own consumable configuration). Under that
 * self-reference, resolving [ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION]
 * during `onVariants` forces Gradle's variant-aware attribute matching to
 * *observe* this same project's other consumable configurations --
 * including AGP's own `debugRuntimeElements` -- as candidates, marking them
 * "consumed as a variant". AGP's own `afterEvaluate`-driven task creation
 * (`BasePlugin.createTasks` -> ... -> Kotlin Gradle Plugin's
 * `KotlinCompilationDependencyConfigurationsFactories`, which calls
 * `debugRuntimeClasspath.extendsFrom(debugRuntimeElements)` while wiring the
 * Kotlin compilation for `compileDebugJavaWithJavac`) then fails outright:
 * `InvalidUserCodeException: Cannot mutate the hierarchy of configuration
 * ':app:debugRuntimeClasspath' after the configuration's child configuration
 * ':app:debugRuntimeElements' was consumed as a variant.` This is not an
 * attribute-ambiguity problem (targeting the consumable configuration by
 * explicit name instead of by attribute match reproduces the identical
 * failure) -- it is inherent to resolving *any* self-referencing project
 * dependency before AGP/KGP have finished mutating their own configurations
 * for that project. §4.5c's acceptance criteria explicitly require the
 * both-plugins-one-module case to "stay green", so this is not an edge case
 * this plugin can leave broken.
 *
 * **The fix, verified against both fixtures with a control (the broken
 * onVariants-only version reproduces the crash; this version does not, and
 * still passes the cross-project `R.string.hello` compile proof):** keep
 * registering the `onVariants` callback -- AGP requires it, variants cannot
 * be discovered any other way -- but only to *collect* each variant's
 * reference. The actual resolution and `addStaticSourceDirectory` call moves
 * to `project.afterEvaluate`, registered here inside
 * `pluginManager.withPlugin("com.android.application")`'s callback -- which
 * only fires once AGP's own `BasePlugin.apply()` has already run and already
 * registered its own `afterEvaluate` task-creation listener. Since
 * `afterEvaluate` listeners fire in registration order, this plugin's
 * listener runs strictly after AGP/KGP have finished mutating this project's
 * own configurations, so the self-reference case no longer races them.
 * **This corrects §4.5c's premise that `afterEvaluate` is "proven silently
 * too late"** -- that finding (see "abandoned attempt" below) was recorded
 * under AGP 8.5.2, where AGP snapshots each variant's resource
 * source-directory list before `afterEvaluate` fires; empirically, under the
 * AGP 9.1.0 floor this plugin now targets, that snapshot-before-afterEvaluate
 * behavior no longer holds -- `addStaticSourceDirectory`, called from
 * `afterEvaluate`, still reaches AGP's resource merge. PLAN.md §4.5c's text
 * itself was not updated to reflect this (out of scope for the change that
 * found it); a human should reconcile the spec with this KDoc.
 *
 * **The landmine §4.5c's probe exposed, and how this plugin avoids it.**
 * `addStaticSourceDirectory(String)` takes a plain path with no `Provider`
 * and carries no task dependency of its own -- resolving
 * [ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION] purely to read a path off
 * disk (as this plugin's `afterEvaluate` block does) therefore does **not**
 * pull `:i18n:validateStrings` into any task graph, silently bypassing
 * §4.5b's whole fail-fast premise. This plugin restores the gate a different
 * way: `preBuild` is made to `dependsOn` the resolvable `Configuration`
 * object itself (not its `.files`) -- `Configuration` implements `Buildable`,
 * the same load-bearing fact the pre-§4.5c redesign already relied on, so
 * Gradle carries the implicit dependency on `:i18n:validateStrings` (via the
 * consumable configuration artifact's `builtBy`) into the app's real task
 * graph. **This is the entire design**: resolving the configuration *for a
 * path* (at configuration time, for its own sake) runs nothing, while
 * building the app -- which always runs `preBuild` -- runs
 * `:i18n:validateStrings` first. A sync that only reads the variant model
 * therefore stays free, while a real build still gates on validation.
 *
 * **Resolution failures stay loud.** No lenient artifact view is used
 * anywhere in this file: resolving [ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION]
 * against a missing project, or a project that never applied
 * `net.sarazan.articulate`, still surfaces Gradle's own "Could not
 * resolve..." diagnostics, which name the project path and the missing
 * attribute/variant. If resolution *succeeds* but yields anything other than
 * exactly one file -- which should not happen for a correctly configured
 * `net.sarazan.articulate` project, but is not assumed -- this plugin throws
 * its own [GradleException] naming the configured
 * [ArticulateAndroidExtension.i18nProject] path and the fix, rather than
 * letting AGP fail confusingly on a null or ambiguous path later.
 *
 * **§4.5b's `addStaticSourceDirectory` alternative -- prototyped and
 * abandoned under AGP 8.5.2, documented here as history because a wrong spec
 * is more useful reported than silently routed around, and because §4.5c's
 * fix depends on understanding exactly what changed.** The design's whole
 * premise ("the IDE resolves the path at sync with nothing executed... this
 * is the whole fix") requires wiring `SourceDirectories.addStaticSourceDirectory(String)`
 * at the i18n-owning project's real, always-on-disk source directory. That
 * API takes a plain `String`, not a `Provider`, so the absolute path has to
 * be resolved *eagerly*, at configuration time -- and empirically, against a
 * real AGP 8.5.2 build (`compileDebugJavaWithJavac` +
 * `compileReleaseJavaWithJavac`, i.e. two variants), there turned out to be
 * no point in the configuration phase where that eager resolution was both
 * safe and effective:
 *
 *  - Resolved eagerly right after registering the resolvable configuration
 *    above (still inside `pluginManager.withPlugin("com.android.application")`'s
 *    callback, which runs *synchronously nested* inside AGP's own plugin
 *    application, before this module's `android { }` block has even run):
 *    the exception aborts script evaluation mid-way, before `compileSdk` is
 *    set, producing a confusing secondary "compileSdkVersion is not
 *    specified" failure alongside the real one.
 *  - Resolved inside `androidComponents.onVariants { }` (fixes the above --
 *    fires only once this module's whole DSL is finalized): **under AGP
 *    8.5.2** fails differently, `Cannot create variant
 *    'android-manifest-metadata' after dependency configuration
 *    ':app:debugApiElements' has been resolved`. AGP 8.5.2 creates each
 *    variant's own configurations progressively while processing that
 *    variant's `onVariants` callbacks; resolving *any* configuration in this
 *    project -- including our own, unrelated one -- trips Gradle's rule
 *    against creating further configurations in that project afterward, and
 *    with two variants requested, the *next* variant's configuration
 *    creation is what breaks. **§4.5c's probe established this does not
 *    reproduce under AGP 9.1** -- resolving inside `onVariants` with two
 *    variants requested compiles cleanly, with a control (the same code
 *    genuinely still fails under 8.5.2) proving the difference is the AGP
 *    version, not an accident of the probe. This is the window §4.5c uses.
 *  - Resolved from `project.afterEvaluate { }` (registered after
 *    `onVariants`, so by construction after every variant has already been
 *    processed -- confirmed this avoids the above, under AGP 8.5.2): no
 *    exception, but the directory never reaches the merge. AGP snapshots
 *    each variant's resource source-directory list before `afterEvaluate`
 *    fires, so anything added there is silently too late -- not merely
 *    unresolved, structurally invisible to the merge.
 *
 * Under AGP 8.5.2, the safe-to-resolve window (after all variants) and the
 * effective-to-add window (during each variant's own `onVariants`, before
 * any configuration in the project has been resolved) did not overlap --
 * that made `addStaticSourceDirectory` a dead end *under that floor*, not a
 * bug in the implementation to keep working around. **The 2026-08-03 D9
 * revision moved the Android floor to AGP 9.1.0 specifically because it
 * closes this gap** (PLAN.md §E2). This historical record stays accurate for
 * 8.5.2 as a description of *that floor's* behavior.
 *
 * The above ("§4.5c correction, found implementing it 2026-08-05") described
 * this plugin's actual, shipped behavior at the time: `onVariants` collecting
 * variant references, `project.afterEvaluate` resolving the configuration and
 * calling `addStaticSourceDirectory` on each collected variant, verified
 * under AGP 9.1 with a control against the 8.5.2-era dead end. **That is no
 * longer what this plugin does.** It shipped, it passed its suite (250/0/0),
 * and it is kept above, unedited, as history -- the paragraph immediately
 * below is what replaced it and why.
 *
 * **SECOND AMENDMENT, 2026-08-06 (PLAN.md §4.5c) -- the registration
 * mechanism changes again, and this time supersedes the Variant API
 * approach entirely, not just its resolution window.**
 *
 * Two findings, each independently verified, forced the change:
 *
 *  - **The Variant API registration (`addStaticSourceDirectory`, from either
 *    `onVariants` or `afterEvaluate` -- both variants of it above) is proven
 *    blind to Android Studio's editor model, by a two-sided human
 *    experiment.** With only that mechanism wired, `R.string.hello` was red
 *    in Studio's editor although the build compiled it cleanly. It resolved
 *    the instant the identical directory was *also* added by hand on the DSL
 *    consumer side (`sourceSets["main"].res.srcDir(...)`, outside this
 *    plugin, at ordinary script-evaluation time) -- and went red again the
 *    moment that hand-added DSL line was removed, with the Variant API
 *    registration still in place and the build still green throughout. The
 *    build was never the problem; Studio's editor model reads the DSL, not
 *    the Variant API's `Sources` interface.
 *  - **AGP rejects a lazy (`Provider`) payload in the DSL SourceSet API, by
 *    design.** An attempt to keep the DSL registration lazy -- feeding
 *    `sourceSets["main"].res.srcDir(...)` a `Provider`-backed value instead
 *    of a literal path -- fails outright with AGP's own diagnostic: *"You
 *    cannot add Provider instances to the Android SourceSet API ... use the
 *    Sources interface"* (reproduced as 14 test failures). The DSL API only
 *    accepts a literal, already-resolved path.
 *
 * **Ruled and shipped mechanism:** resolve
 * [ARTICULATE_ANDROID_RES_INCOMING_CONFIGURATION] eagerly, inside
 * `androidComponents.finalizeDsl { }`, and register the resolved absolute
 * path directly via `androidExtension.sourceSets["main"].res.srcDir(path)`.
 * `addStaticSourceDirectory` and the `onVariants` variant-collection loop --
 * both described in the history above -- are deleted outright: one
 * mechanism, one registration, no `Provider`, no `afterEvaluate`.
 *
 * **Why `finalizeDsl` is the window -- three reasons, not one:**
 *  1. It runs *after* the consumer's `articulateAndroid { i18nProject = ... }`
 *     extension block has already executed, so [ArticulateAndroidExtension.i18nProject]
 *     is a real, consumer-supplied value by the time this callback reads it
 *     -- the same ordering hazard this plugin's `apply()` already defuses
 *     for the `Provider`-based dependency below applies here too, and
 *     `finalizeDsl` defuses it for this eager read the same way.
 *  2. The DSL is still mutable -- that is `finalizeDsl`'s entire documented
 *     purpose, and it is what makes a literal-path
 *     `sourceSets["main"].res.srcDir(...)` call legal at all.
 *  3. It runs *before* AGP creates the per-variant consumable configurations
 *     (`debugRuntimeElements` etc.) whose consume-marking is what made the
 *     self-application case (`articulateAndroid { i18nProject = ":app" }`,
 *     both plugin IDs on one module) explode inside `onVariants` under the
 *     old mechanism -- see the "§4.5c correction" section above for the
 *     exact stack trace. Those configurations cannot be marked "consumed as
 *     a variant" if they do not exist yet, so this is not a workaround for
 *     that race, it removes the precondition for it: the self-apply fixture
 *     stays green under `finalizeDsl` without needing the
 *     `onVariants`-collect/`afterEvaluate`-resolve split the previous
 *     mechanism required.
 *
 * The `preBuild.dependsOn(<Configuration>)` gate immediately below is
 * unchanged by this amendment -- it was never about which API adds the
 * directory, only about whether resolving the configuration is wired into a
 * real build's task graph, and that wiring is independent of the
 * registration call.
 *
 * **What is proven and what is not.** 250/0/0 across the whole matrix
 * (cross-project, both-plugins-one-module, isolated projects,
 * configuration-cache reuse, and the red-first `validateStrings`-gate test)
 * proves the build path is unaffected. The consumer half of the two-sided
 * editor experiment above proves *some* DSL `srcDir` registration is visible
 * to Studio's sync. **What that experiment does NOT establish is whether a
 * registration added specifically at `finalizeDsl` time is visible the same
 * way** -- the consumer experiment added its line at ordinary
 * script-evaluation time, an earlier window than `finalizeDsl`. That gap is
 * the residual risk, closed only by a human syncing this plugin's own output
 * in Android Studio and reporting `R.string` resolved with nothing built.
 * PLAN.md §4.5's open-defect entry is amended to say so and stays open until
 * that happens. If `finalizeDsl`-time registration turns out not to be
 * editor-visible, the documented fallback is the consumer one-liner, which
 * the experiment above already proved works.
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
            // Configuration implements Buildable, so dependsOn(Configuration)
            // -- passing the Configuration object itself, exactly as the
            // pre-§4.5c ResolveArticulateAndroidResTask.resolvedRes did --
            // carries the implicit dependency on :i18n:validateStrings (via
            // articulateAndroidResElements' artifact builtBy) into a real
            // build's task graph. preBuild is the one lifecycle task both
            // preDebugBuild and preReleaseBuild depend on, so this covers
            // every variant without a per-variant edge. This is what makes
            // "resolving-for-a-build runs validateStrings" true again even
            // though the finalizeDsl registration below (used purely to read
            // a path) does not carry any task dependency of its own.
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
            androidComponents.finalizeDsl {
                val registered = extension.androidStringsDir.asFile.orNull
                    ?: extension.i18nProject.get().let { path ->
                        project.rootDir
                            .resolve(path.trimStart(':').replace(':', '/'))
                            .resolve("src/main/strings")
                    }
                androidExtension.sourceSets.getByName("main").res.srcDir(registered)
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
