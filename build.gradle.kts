import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.clessira"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        bundledPlugin("Git4Idea")
        pluginVerifier()
        zipSigner()
    }

    // Gson and kotlinx-coroutines are bundled in the IntelliJ Platform; the
    // plugin zip must stay dependency-free (classloader conflicts otherwise).
    compileOnly("com.google.code.gson:gson:2.10.1")

    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id = "com.clessira.jetbrains"
        name = "Clessira"
        version = providers.gradleProperty("pluginVersion").get()
        description = """
            Connects JetBrains IDEs to the Clessira macOS menu bar time tracker.
            Notifies Clessira when you switch Git branches so it can prompt for a
            time entry, lets you search and start activities from the IDE, and
            shows the currently tracked activity with elapsed time in the status
            bar. Requires the Clessira app for macOS with the editor integration
            enabled; the plugin is a no-op on other operating systems.
        """.trimIndent()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
        vendor {
            name = "Clessira"
            email = "hello@clessira.app"
            url = "https://clessira.app"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
