plugins {
    java
    id("io.papermc.paperweight.userdev") version "1.7.1"
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.example"
version = project.findProperty("plugin_version")?.toString() ?: "1.0.0"
description = "让任意实体骑乘任意实体"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.20.6-R0.1-SNAPSHOT")
    // Add compileOnly dependencies here, e.g.:
    // compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
}

tasks.shadowJar {
    archiveFileName.set("${project.name}-${project.version}.jar")
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(
            "version" to project.version,
            "name" to project.name,
            "description" to project.description
        )
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
