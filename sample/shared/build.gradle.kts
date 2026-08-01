// KMP is present here to exercise Articulate's *optional* shared-keys
// question (PLAN.md §14) -- not because Articulate itself requires it. See
// the sample's own README.
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.10"
    id("com.android.library") version "8.5.2"
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
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
}
