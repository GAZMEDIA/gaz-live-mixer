plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ng.gaz.ai"
    compileSdk = 34
    defaultConfig {
        applicationId = "ng.gaz.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
    }
    signingConfigs {
        create("release") {
            // Fixed key so every new version installs over the old one
            storeFile = file("gazai-release.jks")
            storePassword = "gazai-2026"
            keyAlias = "gazai"
            keyPassword = "gazai-2026"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
