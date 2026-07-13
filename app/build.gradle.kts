plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "no.bellaybestia.codexaudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.bellaybestia.codexaudio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appAuthRedirectScheme"] = "codexaudio"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))

    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:network-abs"))
    implementation(project(":core:auth"))
    implementation(project(":core:player"))

    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.appauth)
    implementation(libs.kotlinx.coroutines.android)
}
