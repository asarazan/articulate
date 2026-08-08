// Android Studio's default per-module shape, matching sample/i18n/build.gradle.kts
// (PLAN.md §15.6 lane A / §4.5/§13): its own `plugins {}` block, version-qualified,
// not applied from the root script. The version string is ignored for resolution --
// settings.gradle.kts's `pluginManagement { includeBuild("..") }` substitutes this
// module's build under development instead of fetching from the Portal.
plugins {
    id("net.sarazan.articulate") version "0.1.0"
}

articulate {
    sourceLanguage = "en"
    ios {
        // Deliberately NOT PLAN.md §15.4's stale "ios/App/Shared.xcstrings" --
        // the wizard-seeded layout (§15.6 A0) put the SwiftUI app at
        // iosApp/iosApp/, not ios/App/. The spec is stale here, not this path.
        catalog = file("../iosApp/iosApp/Shared.xcstrings")
    }
}
