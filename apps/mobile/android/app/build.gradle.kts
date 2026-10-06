plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Public build inputs (gradle.properties / -P). Secrets never flow through here.
fun prop(name: String): String = providers.gradleProperty(name).getOrElse("")

val apiBaseUrl = prop("fortress.apiBaseUrl")
val issuerKeys = prop("fortress.issuerKeys")
val expectedSigner = prop("fortress.expectedSignerSha256")
val playProject = prop("fortress.playCloudProjectNumber").ifBlank { "0" }
val releaseGate = prop("fortress.releaseGate").toBoolean()

android {
    namespace = "com.fortress.upi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fortress.upi"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "ISSUER_KEYS", "\"$issuerKeys\"")
        buildConfigField("String", "EXPECTED_SIGNER_SHA256", "\"$expectedSigner\"")
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${playProject}L")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing config is injected by the release pipeline (Play App Signing upload
            // key from the CI secret store / HSM-backed signer) — never committed.
        }
        debug {
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Security-relevant checks are release blockers.
        fatal += setOf(
            "AllowBackup", "HardcodedDebugMode", "SetJavaScriptEnabled", "TrustAllX509TrustManager",
            "BadHostnameVerifier", "ExportedService", "ExportedReceiver", "UnsafeImplicitIntentLaunch",
            "SecureRandom", "GetInstance", "PackageManagerGetSignatures", "UnspecifiedRegisterReceiverFlag",
        )
    }
}

// Release gate: refuses to build a production artifact with placeholder pins or
// missing trust anchors. Enabled in the release pipeline with -Pfortress.releaseGate=true.
// Values are copied into locals so the task is configuration-cache safe.
val verifyReleaseHardening = tasks.register("verifyReleaseHardening") {
    val nsc = layout.projectDirectory.file("src/main/res/xml/network_security_config.xml").asFile
    val gate = releaseGate
    val keys = issuerKeys
    val signer = expectedSigner
    val base = apiBaseUrl
    inputs.file(nsc)
    doLast {
        if (gate) {
            check(!nsc.readText().contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")) {
                "network_security_config.xml still contains placeholder SPKI pins"
            }
            check(keys.isNotBlank()) { "fortress.issuerKeys must be set for release builds" }
            check(signer.isNotBlank()) { "fortress.expectedSignerSha256 must be set for release builds" }
            check(base.startsWith("https://") && !base.contains(".example")) { "fortress.apiBaseUrl must be the production HTTPS origin" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyReleaseHardening) }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:security"))
    implementation(project(":core:offline"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.camera.mlkit.vision)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
