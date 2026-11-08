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
        minSdk = 28
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
            // R8：Compose 官方強調未經 R8 最佳化的 Compose 會明顯變慢
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // 安裝時預先編譯 Compose 內建的基準設定檔，第一次操作就流暢
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // App 主題色取色
    implementation("androidx.palette:palette-ktx:1.0.0")
    // 農民曆（MIT，離線演算）
    implementation("cn.6tail:lunar:1.7.4")
}
