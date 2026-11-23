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

Put common code in src/main/java, client code in src/client/java and assets in
the corresponding resources directories. Client-only mods may omit common Java.
`active` selects the editing target. `vcs` selects the reset target.

## Metadata and packaging

A [metadata] table opts into generated loader descriptors. It requires name,
description, licence and environment (client or *). Authors/contributors are
optional string arrays, icon is a relative resource path, contact is a string
map and entrypoints maps Fabric entrypoint names to class arrays. NeoForge still
uses native @Mod annotations, including explicit physical-side restrictions.

ModKit supplies the mod ID/version, Java requirement, loader pin and explicit
Minecraft range from its registry. Override Minecraft ranges in
`[compatibility."26.3"]` with fabric and/or neoforge string values. Ranges are
independent of the list of selected targets. Fabric depends/recommends tables
hold explicit loader dependency relationships, and metadata.fabric.custom accepts
nested JSON-compatible TOML values. These do not enable build dependencies.

Optional metadata.mixins generates `<mod-id>.mixins.json`. Set package and common
and/or client class arrays. Defaults are required = true, default_require = 1 and
the target Java compatibility level. Registering a mixin does not create its class.
Generated descriptors must not also exist in src/main/resources or
src/client/resources. Binary and sources JARs contain the selected loader's
resolved descriptor and generated mixin configuration. Source JARs preserve code
and assets. Removing a generated mixin declaration removes its generated file.

Omit metadata to retain hand-authored descriptors. ModKit then expands version,
minecraft_version, minecraft_range, loader_version, java_version, enabled dependency
version keys and custom metadataProperties in loader templates only. Sources
retain their original templates. Binary JARs exclude the other loader's descriptor.

Packaging paths are relative to the mod root. licence_file is explicit and a
missing configured file fails the build. licence_entry defaults to
`<filename>_<mod-id>-<loader>` in both JARs. publishing.maven opts into a native
mavenJava publication with packaged client dependencies, without a destination.
Existing Gradle archive, resource and publication configuration remains available.

## Dependencies and builds

Only Minecraft and the selected loader are implicit. Fabric API uses
implementation, ModMenu/YACL clientImplementation and annotations compileOnly.
Boolean opt-ins apply to all applicable targets. A `{ loaders = [...] }` opt-in
restricts a dependency to explicitly selected, supported loaders. Fabric API and
ModMenu skip NeoForge with a diagnostic. YACL has a pin only for 26.3 Fabric.

Overrides require enabled dependencies and pinned versions. Precedence is target,
Minecraft, global, then the embedded registry. Gradle resolves transitive conflicts
normally. Use `[overrides.targets."26.3.fabric".dependencies]`,
`[overrides.minecraft."26.3".dependencies]` or `[overrides.dependencies]`, with
keys such as `fabric_api = "0.153.2+26.3"`. Each consumer selects its targets.

From your mod directory, run `./gradlew -Pmodkit.includeBuild=../bvengo-modkit buildAll`.
Root build/buildAll collect binary and sources JARs into build/libs and remove stale
collected files. Target builds write to versions/<target>/build/libs. Filenames
must be unique. Archive names default to `<mod-id>-<loader>`.

NeoForge checkCommonSide uses JDK 25 jdeps to reject common compiled references to
Minecraft client, Blaze3D, Realms client and NeoForge client namespaces. Members
elsewhere, reflection and third-party closures need separate validation.

From ModKit, run `./gradlew build` for unit/functional tests and plugin validation.
`./gradlew runtimeTest -Pmodkit.test.acceptEula=true` also launches temporary 26.3
clients and a server, accepting the Minecraft EULA for that server. Clients need
a display/OpenGL, such as xvfb-run on Linux. To omit the server, use
`./gradlew runtimeTest --tests '*native clients*'`. These development launch checks
do not establish every consumer's runtime compatibility or feature behaviour.
