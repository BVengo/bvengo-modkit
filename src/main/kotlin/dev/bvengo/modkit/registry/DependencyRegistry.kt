package dev.bvengo.modkit.registry

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target

/** Build dependency identity and placement, separate from per-target version pins. */
internal data class DependencyDefinition(
    val module: String,
    val configuration: String,
    val loaders: Set<String>,
    val repository: String? = null,
    val additionalRepositoryGroups: Set<String> = emptySet(),
    val excludeFabricApi: Boolean = false,
    val skipInapplicable: Boolean = false,
)

/** A requested dependency pin and its origin, before Gradle resolves transitives. */
internal data class ResolvedDependency(
    val key: String,
    val version: String,
    val origin: String,
    val definition: DependencyDefinition,
) {
    val coordinate: String get() = "${definition.module}:$version"
}

/** Resolves only the dependencies required by the three recorded consumers. */
internal object DependencyRegistry {
    val definitions: Map<String, DependencyDefinition> = linkedMapOf(
        "fabric_api" to DependencyDefinition(
            "net.fabricmc.fabric-api:fabric-api", "implementation", setOf("fabric"),
            skipInapplicable = true,
        ),
        "modmenu" to DependencyDefinition(
            "com.terraformersmc:modmenu", "clientImplementation", setOf("fabric"),
            "https://maven.terraformersmc.com/", excludeFabricApi = true, skipInapplicable = true,
        ),
        "annotations" to DependencyDefinition(
            "org.jetbrains:annotations", "compileOnly", setOf("fabric", "neoforge"),
        ),
        "yacl" to DependencyDefinition(
            "dev.isxander:yet-another-config-lib", "clientImplementation", setOf("fabric"),
            "https://maven.isxander.dev/releases", additionalRepositoryGroups = setOf("org.quiltmc.parsers"),
            excludeFabricApi = true,
        ),
    )

    fun resolve(manifest: ModManifest, target: Target): List<ResolvedDependency> {
        val pins = TargetRegistry.load(target)
        val overrides = manifest.dependencyOverrides
        val targetKey = "${target.minecraft}.${target.loader}"

        return definitions.mapNotNull { (key, definition) ->
            if (manifest.dependencies[key] != true ||
                manifest.dependencyLoaders[key]?.contains(target.loader) == false) {
                require(overrides.targets[targetKey]?.containsKey(key) != true) {
                    "overrides.targets.$targetKey.dependencies.$key overrides a dependency disabled on ${target.loader}"
                }
                return@mapNotNull null
            }
            if (target.loader !in definition.loaders) {
                require(definition.skipInapplicable) {
                    "dependencies.$key has no supported definition for ${target.projectName}"
                }
                return@mapNotNull null
            }

            val candidates = listOf(
                overrides.targets[targetKey]?.get(key) to "overrides.targets.$targetKey.dependencies.$key",
                overrides.minecraft[target.minecraft]?.get(key) to "overrides.minecraft.${target.minecraft}.dependencies.$key",
                overrides.global[key] to "overrides.dependencies.$key",
                pins.dependencies[key] to "registry.${target.projectName}.$key",
            )
            val selected = candidates.firstOrNull { it.first != null }
            requireNotNull(selected) {
                "dependencies.$key has no compatible pin for ${target.projectName}. " +
                    "Choose a supported target or supply an explicit pinned override"
            }
            val version = requireNotNull(selected.first)
            requirePinnedVersion(version, selected.second)

            ResolvedDependency(key, version, selected.second, definition)
        }
    }
}
