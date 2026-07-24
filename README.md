# Paper Plugin Template

A minimal, production-ready template for Paper Minecraft plugins using Gradle Kotlin DSL.

## Quick Start

1. **Rename the plugin:**
   - [ ] `settings.gradle.kts` → change `rootProject.name`
   - [ ] `build.gradle.kts` → change `group`
   - [ ] `gradle.properties` → change `plugin_version`, `plugin_author`
   - [ ] `src/main/resources/plugin.yml` → update `name`, `main`, `description`, `author`, commands, permissions
   - [ ] Rename package `com.example.template` → your own package

2. **Set up Gradle wrapper:**
   ```bash
   gradle wrapper
   ```

3. **Build:**
   ```bash
   ./gradlew build
   ```
   Output JAR will be at `build/libs/<PluginName>-<version>.jar`.

4. **Test locally:**
   Drop the JAR into a Paper 1.21.x server's `plugins/` folder.

## What's Included

| Feature | Detail |
|---|---|
| Build system | Gradle 8.10+ (Kotlin DSL) |
| API | Paper 1.21.4 (paperweight-userdev) |
| Java | 21 (toolchain) |
| Packaging | Shadow JAR |
| CI | GitHub Actions — build on push/PR |
| Config | Auto-saved `config.yml` |
| Plugin metadata | `plugin.yml` with Gradle template expansion |

## Dependencies

Add compileOnly dependencies in `build.gradle.kts` and their repositories:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
}
```

## Project Structure

```
src/main/java/com/example/template/
└── TemplatePlugin.java       # Main plugin class
src/main/resources/
├── plugin.yml                # Plugin descriptor (template-expanded)
└── config.yml                # Default configuration
```
