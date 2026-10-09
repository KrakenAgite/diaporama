plugins {
    alias(libs.plugins.android.application) apply false
    // Fixe la version de Kotlin utilisée par le support Kotlin intégré d'AGP
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
