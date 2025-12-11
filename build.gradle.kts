plugins {
    id("org.jetbrains.intellij.platform") version "2.0.1"
    kotlin("jvm") version "2.2.0"
}

group = "com.envrunner"
version = "1.5.0"

repositories {
    mavenCentral()
    intellijPlatform.defaultRepositories()
}

dependencies {
    intellijPlatform {
        local("/Users/evgenii.truuts/Applications/WebStorm.app")

        instrumentationTools()
    }
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
    }

    // Disable generation of searchable options (not needed for this plugin and
    // may fail on newer platform snapshots with IndexOutOfBoundsException)
    buildSearchableOptions {
        enabled = false
    }
    jarSearchableOptions {
        enabled = false
    }
}

// Use Java 21 toolchain for building against IntelliJ Platform 2025.2
kotlin {
    jvmToolchain(21)
}
