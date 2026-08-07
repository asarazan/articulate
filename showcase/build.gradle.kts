plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    // Kotlin's own Compose compiler plugin — required to compile @Composable code under
    // Kotlin 2.x. This is NOT the multiplatform Compose UI toolkit (that dependency has
    // been removed per PLAN.md §15.0); this compiler plugin stays regardless.
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
}