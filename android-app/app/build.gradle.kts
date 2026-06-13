plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

fun getAppVersion(): String {
    val envVersion = System.getenv("APP_VERSION")
    if (!envVersion.isNullOrEmpty()) {
        return envVersion
    }
    val ver = gitVersion.getOrElse("")
    return if (ver.isEmpty()) "1.0-dev" else ver
}

android {
    namespace = "com.airmini.sync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airmini.sync"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = getAppVersion()
        manifestPlaceholders["appName"] = "AirMini Sync"
        resourceConfigurations.add("en")
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
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "../release.keystore"
            val keystorePass = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val aliasName = System.getenv("KEY_ALIAS") ?: "airmini-sync-release-key"
            val aliasPass = System.getenv("KEY_PASSWORD") ?: ""

            val keystoreFile = file(keystorePath)
            if (keystoreFile.exists() && keystorePass.isNotEmpty() && aliasPass.isNotEmpty()) {
                storeFile = keystoreFile
                storePassword = keystorePass
                keyAlias = aliasName
                keyPassword = aliasPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "AirMini Sync Debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appName"] = "AirMini Sync"
            val rc = signingConfigs.findByName("release")
            if (rc != null && rc.storeFile != null) {
                signingConfig = rc
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
