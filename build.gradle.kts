import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            IntelliJPlatformType.WebStorm,
            providers.gradleProperty("platformVersion").get(),
        )

        // The reworked Terminal API lives in a separate content module with its own
        // classloader, so bundledPlugin alone does not put it on the compile classpath.
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledModule("intellij.terminal.frontend")
    }

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
    compilerOptions {
        // Match the stdlib bundled with the target platform (2026.2 ships 2.4.0).
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.andy.claudesessions"
        name = "Claude Sessions"
        version = providers.gradleProperty("pluginVersion").get()
        vendor {
            name = "andy"
        }
        ideaVersion {
            sinceBuild = "262"
            // Do not pin an upper bound; the plugin should survive minor IDE updates.
            untilBuild = provider { null }
        }
    }
}
