package dev.bvengo.modkit.target

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.TargetPins
import dev.bvengo.modkit.tasks.GenerateModMetadata
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/** Attaches generated resources through source-set providers and normal Gradle task wiring. */
internal object TargetMetadata {
    fun configure(
        project: Project, manifest: ModManifest, target: Target, pins: TargetPins,
        extension: ModKitTargetExtension,
    ) {
        if (manifest.metadata == null) {
            return
        }
        val paths = listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "${manifest.id}.mixins.json")
        val generator = project.tasks.register("generateModMetadata", GenerateModMetadata::class.java) {
            group = "build"
            description = "Generates loader and mixin descriptors from mod.toml."
            descriptors.set(extension.metadataProperties.map { MetadataRenderer.render(manifest, target, pins, it) })
            outputDirectory.set(project.layout.buildDirectory.dir("generated/modkit/resources"))
            listOf("main", "client").forEach { sourceSet ->
                existingDescriptors.from(project.fileTree(project.rootDir.resolve("src/$sourceSet/resources")) {
                    include(paths)
                })
            }
        }
        project.extensions.getByType(SourceSetContainer::class.java).getByName("main").resources
            .srcDir(generator.flatMap { it.outputDirectory })
    }
}
