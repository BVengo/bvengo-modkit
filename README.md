# ModKit

Shared Gradle tooling for BVengo's Minecraft mods. Requires Java 25 and Gradle
9.6.0. Currently supports Minecraft 26.3 on Fabric and NeoForge.

## Setup

Add these files beside your mod's Gradle wrapper. Keep mod-specific dependencies,
loader descriptors, entrypoints and compatibility ranges in the consumer.

`settings.gradle.kts`

```kotlin
pluginManagement {
    providers.gradleProperty("modkit.includeBuild").orNull?.let { path ->
        val directory = file(path)
        require(directory.resolve("settings.gradle.kts").isFile) {
            "Invalid ModKit build directory $directory"
        }
        includeBuild(directory)
    }
    repositories {
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases/")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("dev.bvengo.modkit.settings") version "0.1.0" }
rootProject.name = "example"
```

`mod.toml`

```toml
schema = 1

[mod]
id = "example"
group = "dev.bvengo"
version = "1.0.0"

[targets]
minecraft = ["26.3"]
loaders = ["fabric", "neoforge"]
vcs = "26.3-fabric"
```

`stonecutter.gradle.kts`

```kotlin
plugins { id("dev.bvengo.modkit.controller") }
stonecutter.active("26.3-fabric")
```

`build.gradle.kts`

```kotlin
plugins { id("dev.bvengo.modkit.target") }
```

Put common code in `src/main/java`, client code in `src/client/java` and resources
in the corresponding `resources` directories. `active` selects the editing
target. `vcs` selects the target restored by Stonecutter's reset task.

## Build

Run from your mod directory, adjusting the local ModKit path as needed.
ModKit is not published yet, so supply the property on each invocation.

```sh
./gradlew -Pmodkit.includeBuild=../bvengo-modkit buildAll
```

JARs are written to `versions/<minecraft>-<loader>/build/libs/`.
Run `./gradlew build` from ModKit to build and test the plugins.
