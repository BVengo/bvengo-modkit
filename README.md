# ModKit

Shared Gradle tooling for BVengo's Minecraft mods. Requires Java 25 and Gradle
9.6.0. Includes Minecraft 26.1, 26.2 and 26.3 build targets for Fabric and NeoForge.

## Setup

Add these files beside your mod's Gradle wrapper. Keep loader descriptors,
entrypoints, compatibility ranges and exceptional dependencies in the consumer.

`settings.gradle.kts`

```kotlin
pluginManagement {
    providers.gradleProperty("modkit.includeBuild").orNull?.let { path ->
        val directory = file(path)
        require(directory.resolve("settings.gradle.kts").isFile) {
            "Invalid ModKit build directory $directory"
        }
        includeBuild(directory.absolutePath)
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

## Dependencies

Only Minecraft and the selected loader are added automatically. Enable shared
dependencies in `mod.toml` as needed.

```toml
[dependencies]
fabric_api = true
modmenu = true
annotations = true

[overrides.targets."26.3.fabric".dependencies]
fabric_api = "0.153.2+26.3"
```

Fabric API uses `implementation`. ModMenu uses `clientImplementation`. Both are
skipped with a diagnostic on NeoForge. Annotations use `compileOnly` on either
loader. `yacl = true` uses `clientImplementation` and has a registry pin only
for 26.3 Fabric. Enabling it on NeoForge fails.

Version precedence is target override, Minecraft override, global override,
then the embedded registry. Other override tables are
`[overrides.minecraft."26.3".dependencies]` and `[overrides.dependencies]`.
Overrides require enabled dependencies and pinned versions. Gradle still
resolves transitive version conflicts normally.

Shared Minecraft, loader and dependency pins live in the embedded
[target registry](src/main/resources/dev/bvengo/modkit/registry/minecraft).
Each mod selects its targets explicitly. Adding a registry entry does not
automatically add that target to every mod.

Enabled dependency pins are available as `${fabric_api_version}`,
`${modmenu_version}`, `${annotations_version}` and `${yacl_version}` in loader
descriptors. Keep requiredness, optional integrations and runtime compatibility
ranges explicit in those descriptors. Adding a build dependency does not
generate loader dependency metadata.

## Build

Run from your mod directory, adjusting the local ModKit path as needed.
ModKit is not published yet, so supply the property on each invocation.

```sh
./gradlew -Pmodkit.includeBuild=../bvengo-modkit buildAll
```

NeoForge `check` runs `checkCommonSide` using JDK 25's `jdeps`. Keep references
to Minecraft client, Blaze3D, Realms client and NeoForge client packages in
`src/client`. This checks direct compiled class dependencies, including generic
signatures and dormant bodies. Side-restricted members outside those packages,
reflection and third-party dependency closures need separate validation.

Root `build` and `buildAll` collect binary and sources JARs into `build/libs/`,
removing stale collected artifacts. Collected filenames must be unique.
Individual target builds write to `versions/<minecraft>-<loader>/build/libs/`.
Run `./gradlew build` from ModKit to build and test the plugins.

Run `./gradlew runtimeTest -Pmodkit.test.acceptEula=true` from ModKit for game
launch tests. This accepts the Minecraft EULA for a temporary local test server.
Clients require a display with OpenGL support. On Linux, `xvfb-run -a` can supply
a virtual display. Run only client tests with
`./gradlew runtimeTest --tests '*native clients*'` without accepting the server EULA.
These launch checks cover development runs on 26.3. Build targets alone do not
establish runtime compatibility or feature behaviour for a consumer mod.

## Resource and archive options

Optional configuration below the plugins block in `build.gradle.kts` or
`build.gradle` uses the same syntax for both DSLs.

```kotlin
modkit {
    archiveName.set("example")
    licenceFile.set(rootProject.layout.projectDirectory.file("LICENSE.md"))
    licenceEntry.set("LICENSE_example")
    metadataProperties.put("minecraft_range", ">=26.3 <26.4")
}
```

Archive names default to `<mod-id>-<loader>`. Licences require an explicit file
and default to the entry name `<filename>_<mod-id>-<loader>` in both JARs.

Loader descriptors support `${version}`, `${minecraft_version}`,
`${loader_version}`, `${java_version}` and custom metadata keys. The binary
excludes the other loader's descriptor. Sources retain both original templates.
