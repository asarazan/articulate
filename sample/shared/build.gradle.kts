// KMP is present here to exercise Articulate's *optional* shared-keys
// question (PLAN.md §14) -- not because Articulate itself requires it. See
// the sample's own README.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(17)
    androidTarget()
    jvm()

    sourceSets {
        commonMain.dependencies {
        }
    }
}

android {
    namespace = "net.sarazan.articulate.sample.shared"
    compileSdk = 37
    defaultConfig {
        minSdk = 24
    }
}
