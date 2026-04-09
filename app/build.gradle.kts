plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.drsr.hotspot_adb"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.drsr.hotspot_adb"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("androidx.preference:preference:1.2.1")
}
