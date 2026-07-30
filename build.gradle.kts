// Root build script deliberately applies nothing. Shared configuration is NOT
// pushed through allprojects/subprojects blocks — those break project isolation
// and configuration caching, which milestone 4's plugin work depends on. Each
// module configures itself; convention plugins arrive if duplication warrants.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}
