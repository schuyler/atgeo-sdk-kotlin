plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied (AGP compiles Kotlin itself); declared to pin the Kotlin
    // Gradle plugin to the library's version.
    alias(libs.plugins.kotlin.android) apply false
}
