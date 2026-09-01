plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.nihal.callassist"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.nihal.callassist"
        minSdk = 29
        targetSdk = 35
        versionCode = 68
        versionName = "3.38"
    }

    // Release signing reads keystore.properties (gitignored); without it the
    // release build is left unsigned.
    val signing = rootProject.file("keystore.properties")
    signingConfigs {
        if (signing.exists()) create("release") {
            val p = signing.readLines().filter { "=" in it }
                .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
            storeFile = rootProject.file(p.getValue("storeFile"))
            storePassword = p["storePassword"]
            keyAlias = p["keyAlias"]
            keyPassword = p["keyPassword"]
        }
    }

    buildTypes {
        release {
            // Shrinks code + resources; names stay readable (see proguard-rules.pro)
            // so the in-app crash reporter's traces remain useful.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
