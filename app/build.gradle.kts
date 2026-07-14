plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "no.bellaybestia.audex"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.bellaybestia.audex"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Fixed debug signing (committed keystore, standard debug passwords): CI
    // runners otherwise mint a fresh debug key per build, which makes every
    // update INSTALL_FAILED_UPDATE_INCOMPATIBLE — forcing uninstall (and losing
    // login + downloads) on each new APK. Debug-only; release will use a real key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("audex-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Readium 3.x uses java.time & friends below minSdk 26's native support.
        isCoreLibraryDesugaringEnabled = true
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
    implementation(libs.androidx.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.okhttp)
    implementation(libs.androidx.fragment.ktx)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
