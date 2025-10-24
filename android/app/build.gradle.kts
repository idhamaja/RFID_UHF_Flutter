plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.rfid_03"          // TODO: ganti
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    defaultConfig {
        applicationId = "com.example.rfid_03"  // TODO: ganti
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = JavaVersion.VERSION_11.toString() }

    buildTypes {
           buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    }

    // Tambahkan hanya jika nanti ada konflik resource .so (boleh dibiarkan)
    packaging {
        resources {
            pickFirsts += listOf("**/*.so")
        }
    jniLibs {
      useLegacyPackaging = true
    }
    }
}

/* === AAR lokal === */
repositories { flatDir { dirs("lib") } }
dependencies {
    // cara paling aman: refer langsung ke file
    implementation(files("lib/UHFJar_V1.4.06.aar"))

}

flutter { source = "../.." }
