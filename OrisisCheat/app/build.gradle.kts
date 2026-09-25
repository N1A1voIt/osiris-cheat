import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // NOTE: KSP (google-adk-kotlin-processor) is only needed for @Tool function
    // registration. AGP 9 built-in Kotlin currently conflicts with KSP source
    // registration; re-add both together if the agent gains @Tool functions
    // (plus android.disallowKotlinSourceSets=false in gradle.properties).
}

android {
    namespace = "com.example.orisischeat"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.orisischeat"
        // ADK for Android requires minSdk 26
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Inject the Gemini API key from local.properties (never commit it).
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${localProps.getProperty("geminiApiKey") ?: ""}\"",
        )

        // Firebase AI Logic config (project identifiers, NOT secrets - they
        // ship inside the APK by design). Copy them from your
        // google-services.json in the Firebase console.
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\"${localProps.getProperty("firebaseApiKey") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_APP_ID",
            "\"${localProps.getProperty("firebaseAppId") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_ID",
            "\"${localProps.getProperty("firebaseProjectId") ?: ""}\"",
        )
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            // ADK's JVM transitive deps (google-auth-library, api-common) ship
            // overlapping META-INF entries; they are not needed on Android.
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/MODULE_INFO",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/module-info.class",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.exifinterface)

    // ADK for Android (agentic framework) - https://adk.dev
    implementation("com.google.adk:google-adk-kotlin-core-android:1.1.0")
    // Cloud Gemini via Firebase AI Logic - the supported way to call Gemini
    // from Android (the GenAI SDK blocks raw API keys/credentials on Android).
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.adk:google-adk-kotlin-firebase-android:1.1.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}