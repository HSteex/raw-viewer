plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firma: in CI il keystore arriva dai secrets (vedi .github/workflows/build.yml).
// Senza keystore (build locale) la release viene firmata con la chiave di debug.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.hsteex.rawviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hsteex.rawviewer"
        minSdk = 29
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "rawviewer"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingStoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // Zoom + decodifica a tile per vedere i JPEG a piena risoluzione
    implementation("me.saket.telephoto:zoomable:0.15.1")
    implementation("me.saket.telephoto:sub-sampling-image:0.15.1")
    implementation("com.squareup.okio:okio:3.9.1")
}
