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

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
        getByName("debug")   { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
