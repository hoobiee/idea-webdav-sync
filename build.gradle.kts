plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.6.0"
}
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
val localIdePath = providers.gradleProperty("localIdePath")
dependencies {
    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath.get())
        } else {
            intellijIdeaCommunity("2025.1.3")
        }
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
// EAP 263 launcher is incompatible with this Gradle plugin's search-index runner.
// The settings page remains accessible through Tools; omit only its search index.
tasks.named("buildSearchableOptions") { enabled = false }
intellijPlatform {
    pluginConfiguration {
        version = "0.2.0"
        ideaVersion {
            sinceBuild = provider { intellijPlatform.productInfo.buildNumber.substringBefore('.') }
            untilBuild = provider { "${intellijPlatform.productInfo.buildNumber.substringBefore('.')}.*" }
        }
    }
}
