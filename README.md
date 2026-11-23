# ModKit

Shared Gradle tooling for BVengo's Minecraft mods. Requires Java 25 and Gradle
9.6.0. Includes Minecraft 26.1, 26.2 and 26.3 targets for Fabric and NeoForge.

## Setup

Keep the Gradle wrapper in each mod. ModKit is not published yet, so use an
explicit composite build or a local Maven installation.

`settings.gradle.kts`

```kotlin
pluginManagement {
    providers.gradleProperty("modkit.includeBuild").orNull?.let { includeBuild(it) }
    repositories {
        providers.gradleProperty("modkit.repository").orNull?.let { maven(it) }
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

`build.gradle.kts`

```kotlin
plugins { id("dev.bvengo.modkit.target") }
```

`stonecutter.gradle.kts`

```kotlin
plugins { id("dev.bvengo.modkit.controller") }
stonecutter.active("26.3-fabric")
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

[dependencies]
fabric_api = true
modmenu = true
annotations = { loaders = ["neoforge"] }

[packaging]
licence_file = "LICENSE.md"

[publishing]
maven = true

[metadata]
name = "Example"
description = "An example mod."
licence = "All Rights Reserved"
environment = "client"
authors = ["Example author"]

[metadata.entrypoints]
client = ["example.FabricEntrypoint"]
modmenu = ["example.ModMenuIntegration"]

[metadata.fabric.depends]
fabric-api = "*"

[metadata.fabric.recommends]
modmenu = "*"

[metadata.mixins]
package = "example.mixin"
client = ["ExampleMixin"]
```

## Running
From the mod directory, run `./gradlew -Pmodkit.includeBuild=../bvengo-modkit buildAll`.
