plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fortress.upi.core.crypto"
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
    testImplementation(libs.junit)
}
