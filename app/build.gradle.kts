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
        // Alpha scheme (owner rule, expanded 2026-07-16 to FOUR digits because
        // three burned too fast): 0.MAJOR.FEATURE.PATCH — bump the last digit
        // per release, roll into the third for feature batches. versionCode
        // strictly +1 every build (the OTA check compares it). History: vc2=0.1,
        // vc3=0.1.1 (shipped as "1.0.0"/"1.1.0", renumbered), …, vc12=0.2.0.
        versionCode = 77
        versionName = "0.4.25.0"

        // OTA endpoints, tried in order (see UpdateManager.bases()). Codex
        // MIRRORS the same manifest + APK, so it's a real second route rather
        // than a guess — and the two hosts fail independently: audex.* has sat
        // on the NPMplus default page (200 + HTML) for long stretches while
        // codex.* served fine, which silently killed every update check.
        buildConfigField("String", "UPDATE_URL", "\"https://audex.bellaybestia.no\"")
        buildConfigField("String", "UPDATE_URL_ALT", "\"https://codex.bellaybestia.no\"")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Bundle the repo-root CHANGELOG.md into assets on every build so the
    // in-app update page can never drift from the real release notes (the
    // Codex changelog-drift lesson: never hand-maintain copies).
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/changelog"))
}

val syncChangelog = tasks.register<Copy>("syncChangelog") {
    from(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    into(layout.buildDirectory.dir("generated/changelog"))
}
tasks.named("preBuild") { dependsOn(syncChangelog) }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:podcasts"))
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
    implementation(libs.compose.material.icons)

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
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
