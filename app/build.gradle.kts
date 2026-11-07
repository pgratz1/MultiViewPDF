import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Developer secrets from the git-ignored `.env` at the repo root (`KEY=value` lines; see `.env.example`),
 * falling back to the process environment. Baked into [BuildConfig] so a fresh install already has the
 * Anthropic key instead of asking for it again under "Read-aloud settings…".
 */
val dotEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.reader().use { load(it) } }.entries.associate { (k, v) -> k.toString() to v.toString().trim().trim('"', '\'') }
} ?: emptyMap()

fun secret(name: String): String = dotEnv[name]?.takeIf { it.isNotBlank() } ?: System.getenv(name) ?: ""

android {
    namespace = "com.pgratz.multiviewpdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pgratz.multiviewpdf"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // arm64-v8a for the Pixel; x86_64 so the same APK runs on the emulator.
            // MuPDF also ships armeabi-v7a/x86, which only bloat the APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        // Empty when no .env / environment variable is set; the app then relies on the key typed in settings.
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${secret("ANTHROPIC_API_KEY")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            // Duplicate metadata from the Anthropic SDK's Jackson/OkHttp dependencies.
            excludes += setOf(
                "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/LICENSE.md", "META-INF/NOTICE.md",
                "META-INF/io.netty.versions.properties", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    // MuPDF: rendering, text selection, and standard PDF annotation editing/saving (AGPL).
    implementation(libs.mupdf.fitz)
    // Claude figure descriptions (Anthropic Java SDK; OkHttp + Jackson).
    implementation(libs.anthropic.java)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
