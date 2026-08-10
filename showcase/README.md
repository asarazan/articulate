# Articulate showcase

A runnable proof of [Articulate](../README.md)'s value prop (PLAN.md §15): native SwiftUI on
iOS, Jetpack Compose (no Compose Multiplatform) on Android, and one shared `strings.xml` source
of truth for copy on both. `showcase/androidApp` and `showcase/iosApp` render the same
"Checklist" app from the same `:i18n` module; `showcase/sharedLogic` carries the presenter and
domain logic, never copy strings, per §14's sealed-type pattern.

This is also a real, separately-wrappered composite build in its own right — its own
`./gradlew`, own version catalog, `pluginManagement { includeBuild("..") }` against the plugin
under development — exercised in CI by the `showcase-human-path` job (`ci.yml`, PLAN.md §15.5).
iOS is not built in CI (Linux-only runners); see §15.7 for the human checklist that covers it.

What follows is the standard Kotlin Multiplatform wizard README for this project's layout.

This is a Kotlin Multiplatform project targeting Android, iOS.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/sharedLogic](./sharedLogic/src) is for the code that will be shared between app targets in the project.
  The most important subfolder is [commonMain](./sharedLogic/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/sharedUI](./sharedUI/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./sharedUI/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./sharedUI/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./sharedUI/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :sharedUI:testAndroidHostTest :sharedLogic:testAndroidHostTest`
- iOS tests: `./gradlew :sharedLogic:iosSimulatorArm64Test`

### Screenshots

_Slot for PLAN.md §15.7's human step: screenshots of both platforms, in `de` and `zh-Hans`, land_
_here once someone has run the Android app and built/run the iOS app in Xcode._

| | Android | iOS |
|---|---|---|
| `de` | _(pending)_ | _(pending)_ |
| `zh-Hans` | _(pending)_ | _(pending)_ |

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
