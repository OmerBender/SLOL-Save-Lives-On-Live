plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rescue360.detector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rescue360.detector"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

val serverWsUrl = providers.gradleProperty("RESCUE360_SERVER_WS_URL").orNull
    ?: System.getenv("RESCUE360_SERVER_WS_URL")
    ?: "ws://<SERVER_HOST>:8000/ws"
val insta360Password = providers.gradleProperty("INSTA360_X4_WIFI_PASSWORD").orNull
    ?: System.getenv("INSTA360_X4_WIFI_PASSWORD")
    ?: ""
buildConfigField("String", "DEFAULT_SERVER_WS_URL", "\"$serverWsUrl\"")
buildConfigField("String", "DEFAULT_INSTA360_X4_WIFI_PASSWORD", "\"$insta360Password\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true

            excludes += listOf(
                "lib/arm64-v8a/libSnpeHtpV68Skel.so",
                "lib/arm64-v8a/libSnpeHtpV69Skel.so",
                "lib/arm64-v8a/libSnpeHtpV73Skel.so",
                "lib/arm64-v8a/libSnpeHtpV75Skel.so",
                "lib/arm64-v8a/libSnpeHtpV79Skel.so",
                "lib/arm64-v8a/libcalculator_skel.so",
                "lib/x86/*"
            )
        }

        resources {
            excludes += listOf("META-INF/rxjava.properties")
            pickFirsts += listOf("lib/arm64-v8a/libc++_shared.so")
        }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        compose = false
        buildConfig = true
    }
}

dependencies {
    // Insta360 SDK
    implementation("com.arashivision.sdk:sdkcamera:1.10.1")
    implementation("com.arashivision.sdk:sdkmedia:1.10.1")

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.10.0")
    implementation("com.squareup.retrofit2:converter-gson:2.10.0")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
