plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.tecnocreativas.budswidget"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.tecnocreativas.budswidget"
        // Nothing Phone (2a) salió con Android 14 y hoy corre Nothing OS 4 (Android 16).
        // 31 = Android 12: permisos BLUETOOTH_CONNECT y widgets adaptables.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Firmado con la clave debug para que el APK de release se pueda instalar directo.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
