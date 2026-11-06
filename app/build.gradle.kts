plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 雲端建置時以 GitHub Actions 的執行序號當版本號，確保每次都能覆蓋安裝
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// 由 CI 從 Secrets 還原的簽章金鑰（PKCS12）；沒有時退回 debug 簽章
val keystorePath: String? = System.getenv("LIULI_KEYSTORE")

android {
    namespace = "tw.liuli.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.liuli.launcher"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("LIULI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LIULI_KEY_ALIAS")
                keyPassword = System.getenv("LIULI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    // 網頁桌布：以虛擬 https 網域載入內建與 zip 桌布
    implementation("androidx.webkit:webkit:1.11.0")
    // 農民曆（MIT，離線演算）
    implementation("cn.6tail:lunar:1.7.4")
}
