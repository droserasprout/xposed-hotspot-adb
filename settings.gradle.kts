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
        // Note: legacy https://api.xposed.info/ Maven repo removed — modern libxposed is on Maven Central
    }
}

rootProject.name = "HotspotAdb"
include(":app")
