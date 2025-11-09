plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.microsoft.intune.mam") //MAM プラグイン
}

android {
    namespace = "com.hatakemu.android.mamtest"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.hatakemu.android.mamtest"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.7.0"
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.activity:activity-compose:1.10.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // MSAL（Microsoft Authentication Library for Android）
    implementation("com.microsoft.identity.client:msal:6.+")
    // Graph 呼び出し用（軽量に REST 直叩き）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // （JSON パースが必要になったら）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Intune SDK
    implementation(files("libs/Microsoft.Intune.MAM.SDK.aar"))
}

intunemam {
    report.set(true)
    verify.set(true)
}
