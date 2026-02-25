plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.sonya_front"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.sonya_front"
        // adaptive icons in mipmap-anydpi require 26+
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // Kotlin 2 + Compose plugin: compose compiler is managed by the plugin, do not pin legacy versions.
    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
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
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ViewModel для Jetpack Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Wake word (offline): ONNX Runtime for openWakeWord models.
    // TFLite was crashing on some devices during allocateTensors() for melspectrogram.tflite.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // Offline ASR / keyword spotting (Russian): Vosk
    implementation("com.alphacephei:vosk-android:0.3.47")

    // Retrofit для сетевых запросов
    implementation(libs.retrofit)
    // Конвертер для JSON (Moshi - более современный, Gson - классический)
    implementation(libs.converter.moshi)
    implementation(libs.moshi.kotlin)
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Для асинхронных вызовов в Retrofit
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.google.android.gms:play-services-location:21.0.1")
}