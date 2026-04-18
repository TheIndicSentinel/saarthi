pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
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
    // gradle/libs.versions.toml is auto-discovered by Gradle 8+ as the "libs" catalog.
    // Explicitly calling from() here would invoke it twice and fail.
}

rootProject.name = "Saarthi"

include(":app")

// Core modules
include(":core:core-common")
include(":core:core-ui")
include(":core:core-inference")
include(":core:core-memory")
include(":core:core-i18n")
include(":core:core-rag")

// Feature modules
include(":feature:feature-onboarding")
include(":feature:feature-assistant")
include(":feature:feature-money")
include(":feature:feature-kisan")
include(":feature:feature-knowledge")
include(":feature:feature-fieldexpert")
