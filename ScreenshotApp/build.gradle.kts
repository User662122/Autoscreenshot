plugins {
    // Android & Kotlin
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false

    // Google Services plugin (Required for google-services.json)
    id("com.google.gms.google-services") version "4.4.4" apply false

    // Firebase Crashlytics plugin
    id("com.google.firebase.crashlytics") version "2.9.9" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}