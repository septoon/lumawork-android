pluginManagement {
    repositories {
        // Official Google CDN redirector is reachable from this Mac even when dl.google.com is not.
        maven("https://redirector.gvt1.com/edgedl/android/maven2")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://redirector.gvt1.com/edgedl/android/maven2")
        google()
        mavenCentral()
    }
}

rootProject.name = "LumaWork-android"
include(":app")

