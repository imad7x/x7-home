import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing comes from an env file kept outside the project (see tools/build.sh).
val signingEnv = File(System.getenv("X7_SIGNING_ENV") ?: "/docker/phone/secrets/signing.env")
val keys = Properties().apply { if (signingEnv.exists()) signingEnv.inputStream().use { load(it) } }

android {
    namespace = "uk.imad7x.home"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.imad7x.home"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            keys.getProperty("STORE_FILE")?.let {
                storeFile = File(signingEnv.parentFile, it)
                storePassword = keys.getProperty("STORE_PASSWORD")
                keyAlias = keys.getProperty("KEY_ALIAS")
                keyPassword = keys.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            if (keys.getProperty("STORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
