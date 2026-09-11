import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "bombaclip.karasu"
    compileSdk = 35

    defaultConfig {
        applicationId = "bombaclip.karasu"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "3.1"
    }

    signingConfigs {
        val releaseProps = Properties().apply {
            val f = rootProject.file("keystore.properties")
            if (f.exists()) f.inputStream().use { load(it) } else {
                setProperty("storeFile", "keystore/bombaclip.jks")
                setProperty("storePassword", System.getenv("BOMBACLIP_STORE_PASS") ?: "")
                setProperty("keyAlias", "bombaclip")
                setProperty("keyPassword", System.getenv("BOMBACLIP_KEY_PASS") ?: "")
            }
        }
        create("release") {
            storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
            storePassword = releaseProps.getProperty("storePassword")
            keyAlias = releaseProps.getProperty("keyAlias")
            keyPassword = releaseProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}