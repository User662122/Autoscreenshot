plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")

    // Firebase plugins
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.example.autoscreenshot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.autoscreenshot"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {

        release {
            isMinifyEnabled = false
            // No proguard needed for now since you don’t have rules
        }

        debug {
            // Enable Crashlytics in debug builds too (helpful for testing crashes)
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    aaptOptions {
        noCompress("tflite")
    }
}

dependencies {
    // Firebase BOM (keeps all versions compatible)
    implementation(platform("com.google.firebase:firebase-bom:34.6.0"))

    // Firebase Analytics
    implementation("com.google.firebase:firebase-analytics")

    // Firebase Crashlytics
    implementation("com.google.firebase:firebase-crashlytics")

    // Your existing dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
}