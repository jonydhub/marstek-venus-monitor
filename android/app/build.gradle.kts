import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.doloh.marstekwidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.doloh.marstekwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 110
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing: keystore.properties path from MARSTEK_KEYSTORE_PROPERTIES
    // (or android/keystore.properties), or env MARSTEK_KEYSTORE_FILE/_PASSWORD/_ALIAS.
    // Never commit the keystore or its passwords.
    val signingProps = Properties().apply {
        val path = System.getenv("MARSTEK_KEYSTORE_PROPERTIES")
            ?: rootProject.file("keystore.properties").path
        val f = file(path)
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storeFilePath = signingProps.getProperty("storeFile") ?: System.getenv("MARSTEK_KEYSTORE_FILE")
    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = signingProps.getProperty("storePassword") ?: System.getenv("MARSTEK_KEYSTORE_PASSWORD")
                keyAlias = signingProps.getProperty("keyAlias") ?: System.getenv("MARSTEK_KEY_ALIAS") ?: "marstek"
                keyPassword = signingProps.getProperty("keyPassword") ?: System.getenv("MARSTEK_KEY_PASSWORD")
                    ?: System.getenv("MARSTEK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
