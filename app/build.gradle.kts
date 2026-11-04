plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dsh.kidstv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dsh.kidstv"
        minSdk = 23
        targetSdk = 34
        versionCode = 8
        versionName = "1.2.1"
    }

    // 签名文件不进仓库；扔一个 kidstv.jks 到项目根目录就能出正式包，
    // 没有的话自动退回 debug 签名（装得上，只是不能覆盖安装正式版）。
    val keystore = rootProject.file("kidstv.jks")
    signingConfigs {
        if (keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("KIDSTV_STORE_PASS") ?: "kidstv2026"
                keyAlias = System.getenv("KIDSTV_KEY_ALIAS") ?: "kidstv"
                keyPassword = System.getenv("KIDSTV_KEY_PASS") ?: "kidstv2026"
            }
        }
    }

    flavorDimensions += "target"

    productFlavors {
        create("tv") {
            dimension = "target"
        }
        create("phone") {
            dimension = "target"
            applicationIdSuffix = ".phone"
            versionNameSuffix = "-phone"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
