// AGP 9 compiles Kotlin natively ("built-in Kotlin"). Declaring the Kotlin
// Android plugin here (apply false) only pins the Kotlin compiler version that
// AGP and the Compose compiler plugin resolve to; modules never apply it.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
