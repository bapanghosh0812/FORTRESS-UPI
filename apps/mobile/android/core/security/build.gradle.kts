plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fortress.upi.core.security"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:crypto"))
    api(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.integrity)

    testImplementation(libs.junit)
}
