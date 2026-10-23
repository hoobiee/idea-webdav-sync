pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        kotlin("jvm") version (if (providers.gradleProperty("localIdePath").isPresent) "2.4.0" else "2.1.20")
    }
}
rootProject.name = "idea-webdav-sync"
