plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Applied only when app/google-services.json exists (see app/build.gradle.kts).
    id("com.google.gms.google-services") version "4.4.2" apply false
}
