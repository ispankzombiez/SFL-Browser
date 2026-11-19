// Top-level build file. Plugin versions are declared here and applied per-module.
plugins {
    // Reown ships Kotlin 2.2 metadata, which requires AGP 8.10 or later for R8.
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.8" apply false
}
