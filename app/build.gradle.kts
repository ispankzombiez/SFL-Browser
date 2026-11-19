import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing credentials live in signing.properties (git-ignored). CI creates that
// file from repository secrets; locally copy signing.properties.template and fill it in.
// Without it a release build is simply unsigned.
val signingPropertiesFile = rootProject.file("signing.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.sfl.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sfl.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 52
        versionName = "1.1.001"

        // WalletConnect Cloud project id. Overridable at runtime from the settings screen.
        buildConfigField("String", "WC_PROJECT_ID", "\"90b71431a0afd16c0a9f20210c2a87a3\"")

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            if (signingPropertiesFile.exists()) {
                storeFile = file(signingProperties.getProperty("RELEASE_STORE_FILE"))
                storePassword = signingProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = signingProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = signingProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off for now: the WalletConnect SDK relies on Koin/SQLDelight
            // wiring that we cannot exercise on-device yet, and a stripped runtime crash
            // is worse than a larger APK. Turn on once instrumented tests cover connect.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // bcprov and jspecify both ship a multi-release OSGI manifest; neither
            // matters on Android, so the clash is resolved by dropping it.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
            // Licence/notice boilerplate and stub module descriptors collide the
            // same way; keeping one copy of each is enough.
            pickFirsts += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.kotlin_module",
                "**/module-info.class",
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

    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.google.android.material:material:1.12.0")

    // Background polling that keeps firing when the app is swiped away.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // WalletConnect v2 (Reown) — dApp side, used by the injected EIP-1193 provider.
    // 1.6.14+ declares minCompileSdk 37 (Android 17); 1.6.13 is API-identical
    // for every call this app makes and builds against our compileSdk 36.
    implementation("com.reown:android-core:1.6.13")
    implementation("com.reown:sign:1.6.13")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
