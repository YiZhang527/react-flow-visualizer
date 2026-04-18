plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.2.0"
}

group = "com.github.archmap"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.2.1")
    }
    implementation("com.google.code.gson:gson:2.10.1")
}

intellijPlatform {
    pluginConfiguration {
        name = "ArchMap"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "232"
            untilBuild = "241.*"
        }
    }
}

tasks {
    compileJava {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    compileKotlin {
        kotlinOptions.jvmTarget = "17"
    }
    wrapper {
        gradleVersion = "8.5"
    }
}
