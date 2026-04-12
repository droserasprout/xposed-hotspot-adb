plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

detekt {
    config.setFrom("${rootProject.projectDir}/detekt.yml")
}

android {
    namespace = "io.drsr.hotspotadb"
    // API 36 = Android 16
    compileSdk = 36

    defaultConfig {
        applicationId = "io.drsr.hotspotadb"
        // minSdk stays at 35 (Android 15): the module targets Android 15+ only
        minSdk = 35
        targetSdk = 36
        versionCode = 4
        versionName = "2.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "${System.getProperty("user.home")}/.android/release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    // Merge META-INF/xposed/* from src/main/resources into the APK.
    // Modern libxposed modules use these files instead of assets/xposed_init and manifest metadata.
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // Modern libxposed API 101 — replaces legacy de.robv.android.xposed:api:82
    compileOnly("io.github.libxposed:api:101.0.1")
    compileOnly("androidx.preference:preference:1.2.1")
}
