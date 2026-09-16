plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
}

android {
    namespace = "com.vpnsdk"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24

        ndk {
            // Xray upstream currently provides Android binaries for 64-bit ABIs only.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    packaging {
        jniLibs {
            // Needed so executable native binaries (libxray.so/libtun2socks.so) are extracted
            // to nativeLibraryDir where ProcessBuilder can execute them.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // BouncyCastle for ChaCha20-Poly1305 support in the Shadowsocks AEAD reference implementation.
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
