plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere: AGP 9 compiles Kotlin itself. Declaring it pins the
    // Kotlin Gradle plugin version so it matches the compose/serialization plugins.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
