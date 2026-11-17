plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ludo.voicedictation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ludo.voicedictation"
        minSdk = 26
        targetSdk = 34
        versionCode = 70
        versionName = "1.2.49"

        ndk {
            abiFilters.add("arm64-v8a")
        }

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
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            // FastRPC loads the extracted Hexagon DSP binary; don't strip it as ARM64.
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libQnnHtpV79Skel.so")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // QNN-enabled JNI retains the CPU bindings; keep the original AAR on disk, not on the classpath.
    implementation(files("libs/sherpa-onnx-2.0.1-qnn-v79-kotlin-935e5ded.aar"))
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.1")
}
