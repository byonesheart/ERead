pluginManagement {
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            // Fix D8 b/408438353: rematerialization with more than 256 registers.
            classpath("com.android.tools:r8:8.11.18")
        }
    }
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
rootProject.name = "ERead"
include(":app")
