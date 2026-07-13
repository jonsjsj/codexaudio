plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "no.bellaybestia.codexaudio.network.abs"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.retrofit)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.socketio.client)
}
