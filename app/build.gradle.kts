plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 110
val ciVersionName = (project.findProperty("appVersionName") as String?) ?: "1.1.0"

android {
    namespace = "com.weplay.cardtycoon.helper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weplay.cardtycoon.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "OTA_RELEASES_API",
            "\"https://api.github.com/repos/AlfaGideon/WePlay-NewGame/releases/latest\""
        )
    }

    signingConfigs {
        create("universalOta") {
            val envPath = System.getenv("OTA_KEYSTORE_PATH")
            val envType = System.getenv("OTA_KEYSTORE_TYPE")
            val envPass = System.getenv("OTA_KEYSTORE_PASS")
            val envAlias = System.getenv("OTA_KEY_ALIAS")
            val envKeyPass = System.getenv("OTA_KEY_PASS")

            storeFile = if (!envPath.isNullOrBlank()) file(envPath) else file("signing/universal-ota.p12")
            storeType = if (!envType.isNullOrBlank()) envType else "PKCS12"
            storePassword = if (!envPass.isNullOrBlank()) envPass else "cardtycoon_ota_universal"
            keyAlias = if (!envAlias.isNullOrBlank()) envAlias else "cardtycoon"
            keyPassword = when {
                !envKeyPass.isNullOrBlank() -> envKeyPass
                !envPass.isNullOrBlank() -> envPass
                else -> "cardtycoon_ota_universal"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("universalOta")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("universalOta")
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
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device offline OCR for money pills, tile rents, round counter & card digits
    implementation("com.google.mlkit:text-recognition:16.0.0")

    testImplementation("junit:junit:4.13.2")
}
