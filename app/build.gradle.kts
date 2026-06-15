plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.unkwn2.yandexhud"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.unkwn2.yandexhud"
        minSdk = 28
        targetSdk = 34
        versionCode = 37
        versionName = "3.7-a11y-rewrite"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file(
                project.findProperty("RELEASE_STORE_FILE")?.toString()
                    ?: (System.getProperty("user.home") + "/.android/release.keystore")
            )
            storePassword = project.findProperty("RELEASE_STORE_PASSWORD")?.toString() ?: "android"
            keyAlias = project.findProperty("RELEASE_KEY_ALIAS")?.toString() ?: "releasekey"
            keyPassword = project.findProperty("RELEASE_KEY_PASSWORD")?.toString() ?: "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug")   {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

}
