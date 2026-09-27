plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.versatile.runfromjigar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.versatile.runfromjigar"
        // minSdk 21 (Android 5.0) covers effectively all active devices —
        // the Canvas/SurfaceView APIs used here have been stable since
        // API 1, so there's no real reason to go higher.
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
