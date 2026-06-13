import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.intellij.platform") version "2.7.2"
}

group = "com.clessira"

// Single source of truth for the published version. CI sets PLUGIN_VERSION from
// the pushed `vX.Y.Z` git tag (semantic versioning); local/dev builds fall back
// to `pluginVersion` in gradle.properties.
val pluginVersion = providers.environmentVariable("PLUGIN_VERSION")
    .orElse(providers.gradleProperty("pluginVersion"))

version = pluginVersion.get()

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
        version = pluginVersion.get()
        description = """
            <p>
              <b>Clessira for JetBrains</b> connects your IDE to the
              <a href="https://clessira.app">Clessira</a> macOS menu-bar time
              tracker, so your tracked time follows the branch you are actually
              working on — without ever leaving the editor.
            </p>

            <p>
              When you switch Git branches in IntelliJ IDEA, WebStorm, PyCharm,
              GoLand, Rider or any other JetBrains IDE, the plugin tells
              Clessira, which pops up its time-entry prompt for the new branch.
              You can also search and start activities straight from the IDE and
              keep an eye on what you are tracking from the status bar.
            </p>

            <h3>Features</h3>
            <ul>
              <li><b>Branch-aware prompts</b> — switching branches in any open
                  repository triggers a Clessira time-entry prompt, debounced so
                  rebases don't spam you.</li>
              <li><b>Start activities from the IDE</b> — the <code>Clessira:
                  Start Activity…</code> action offers type-ahead search with
                  create-if-missing, from the Tools menu or Find Action.</li>
              <li><b>Live status bar</b> — see the currently tracked activity and
                  elapsed time at a glance; click to open the activity picker or
                  a quick action menu (track, test connection, reconnect,
                  settings, logs).</li>
              <li><b>Configurable &amp; quiet</b> — toggle notifications, tune
                  the debounce window, ignore branches by regex, and choose what
                  the status bar shows.</li>
              <li><b>Private by design</b> — no network port is opened. All
                  traffic flows through a Unix-domain socket inside the Clessira
                  sandbox container and is signed with HMAC, a timestamp and a
                  nonce.</li>
            </ul>

            <h3>Requirements</h3>
            <ul>
              <li><b>macOS only.</b> The plugin is a no-op on Windows and
                  Linux.</li>
              <li>The <a href="https://clessira.app">Clessira macOS app</a> with
                  the editor integration enabled (this starts the local socket
                  the plugin connects to).</li>
              <li>A JetBrains IDE version 2024.2 or newer.</li>
            </ul>

            <p>
              Setup is zero-config: enable the editor integration in Clessira,
              install the plugin, and the status bar shows
              <code>✓ Clessira</code> once the app is reachable.
            </p>
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

// IntelliJ Platform Gradle Plugin 2.x no longer wires buildPlugin into the
// build lifecycle, so a plain `./gradlew build` produces only the jars under
// build/libs. Re-attach it so the installable distribution zip in
// build/distributions is always produced as part of `build`/`assemble`.
tasks.assemble {
    dependsOn(tasks.buildPlugin)
}
