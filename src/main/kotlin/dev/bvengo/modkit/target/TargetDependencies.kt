package dev.bvengo.modkit.target

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.DependencyRegistry
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import java.net.URI

/** Adds opted-in dependencies without replacing Gradle's version conflict resolution. */
internal object TargetDependencies {
    fun configure(project: Project, manifest: ModManifest, target: Target, extension: ModKitTargetExtension) {
        val resolved = DependencyRegistry.resolve(manifest, target)
        DependencyRegistry.definitions.forEach { (key, definition) ->
            if (manifest.dependencies[key] == true && target.loader !in definition.loaders) {
                project.logger.lifecycle("ModKit ${target.projectName}: skipping Fabric-only dependency $key")
            }
        }

        resolved.forEach { dependency ->
            val definition = dependency.definition
            definition.repository?.let { repository ->
                project.repositories.maven {
                    url = URI(repository)
                    content {
                        includeGroup(definition.module.substringBefore(':'))
                        definition.additionalRepositoryGroups.forEach(::includeGroup)
                    }
                }
            }

            val module = project.dependencies.create(dependency.coordinate) as ExternalModuleDependency
            if (definition.excludeFabricApi) {
                module.exclude(mapOf("module" to "fabric-api"))
            }
            project.dependencies.add(definition.configuration, module)
            extension.metadataProperties.put("${dependency.key}_version", dependency.version)
            project.logger.info(
                "ModKit ${target.projectName}: ${dependency.coordinate} on ${definition.configuration} " +
                    "from ${dependency.origin} (requested pin)",
            )
        }
    }
}
