pluginManagement {
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
}
rootProject.name = "P2PChat"
include(":app")
include(":core-common")
include(":core-crypto")
include(":core-database")
include(":transport-ble")
include(":mesh-engine")
