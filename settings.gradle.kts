pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "codex-audio-android"

include(
    ":app",
    ":core:common",
    ":core:domain",
    ":core:catalog",
    ":core:database",
    ":core:network-abs",
    ":core:auth",
    ":core:data",
    ":core:player",
    ":core:designsystem",
    ":feature:home",
    ":feature:library",
    ":feature:player",
    ":feature:downloads",
    ":feature:settings",
)
