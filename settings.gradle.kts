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
        // Reown's transitive deps (Scarlet, kethereum, java-multibase) are
        // published on JitPack only.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SFL-Browser"
include(":app")
