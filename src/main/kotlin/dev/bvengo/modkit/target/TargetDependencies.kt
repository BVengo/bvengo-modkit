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
            if (dependency.key == "modcore") {
                configureModCoreRepository(project)
            }
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

    private fun configureModCoreRepository(project: Project) {
        project.providers.gradleProperty("modcore.artifacts").orNull?.let { path ->
            project.repositories.flatDir {
                dirs(project.rootProject.file(path))
                content { includeGroup("dev.bvengo") }
            }
        }
        project.providers.gradleProperty("modcore.repository").orNull?.let { repository ->
            project.repositories.maven {
                url = URI(repository)
                content { includeGroup("dev.bvengo") }
            }
        }
    }
}
