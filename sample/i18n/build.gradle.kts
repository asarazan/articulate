// Android Studio's default per-module shape: its own `plugins {}` block,
// with a version -- not applied from the root script. This is precisely the
// shape PLAN.md §4.5/§13 identifies as release-blocking: :i18n and :androidApp
// each requesting the plugin classpath independently gives each module its
// own plugin classloader, and :androidApp additionally requests AGP in its
// own block, so its classloader differs from :i18n's by exactly the AGP jars.
plugins {
    id("net.sarazan.articulate") version "0.1.0"
}

articulate {
    sourceLanguage = "en"
    ios {
        catalog = file("../ios/App/Shared.xcstrings")
    }
}
