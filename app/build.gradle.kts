plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guardianlayer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.guardianlayer.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 23
        versionName = "0.4.2-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Test/debug builds use one stable key so APKs produced by separate CI
    // runs can update each other on a physical device. This key is intentionally
    // debug-only and must never be used for a production release.
    signingConfigs {
        create("guardianDebug") {
            storeFile = file("guardian-debug.jks")
            storePassword = "guardian123"
            keyAlias = "guardian-debug"
            keyPassword = "guardian123"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("guardianDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
