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
        // Alpha scheme (owner rule): 0.1 → 0.1.1 → …; patch digit per build,
        // versionCode strictly +1 every build (the OTA check compares it).
        // Earlier builds shipped as "1.0.0"/"1.1.0" — renumbered: vc2=0.1,
        // vc3=0.1.1. versionName is free-form so this is safe.
        versionCode = 7
        versionName = "0.1.5"

        // Default OTA endpoint (the public audex-align host). It serves
        // /audex-latest.json (the version manifest) and /audex.apk. Overridable
        // at runtime by the configured word-sync service URL, if set.
        buildConfigField("String", "UPDATE_URL", "\"https://audex.bellaybestia.no\"")
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
    implementation(libs.timber)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
