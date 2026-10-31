plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "hu.videovago"
    compileSdk = 34

    defaultConfig {
        applicationId = "hu.videovago"
        minSdk = 29          // Android 10+: MediaStore without storage permission, B-frame muxing
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Shared, non-secret signing key so new builds install over old ones.
    signingConfigs {
        create("shared") {
            storeFile = file("videovago.keystore")
            storePassword = "videovago"
            keyAlias = "videovago"
            keyPassword = "videovago"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
