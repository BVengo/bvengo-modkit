package dev.bvengo.modkit.target

import dev.bvengo.modkit.platform.FabricAdapter
import dev.bvengo.modkit.platform.NeoForgeAdapter
import dev.bvengo.modkit.registry.TargetRegistry
import dev.bvengo.modkit.registry.readManifest
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

/** Applies a single loader adapter to an explicitly registered Stonecutter target. */
class ModKitTargetPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val manifest = readManifest(project.providers, project.layout, project.rootDir)
        val target = requireNotNull(manifest.targets.singleOrNull { it.projectName == project.name }) {
            "${project.path} is not a target declared in mod.toml"
        }
        val pins = TargetRegistry.load(target)

        project.pluginManager.apply("dev.kikugie.stonecutter")
        project.pluginManager.apply("java-library")
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        java.withSourcesJar()
        java.toolchain.languageVersion.set(JavaLanguageVersion.of(pins.java))

        project.group = manifest.group
        project.version = "${manifest.version}-mc${target.minecraft}"

        val extension = project.extensions.create("modkit", ModKitTargetExtension::class.java)
        extension.archiveName.convention("${manifest.id}-${target.loader}")
        extension.licenceEntry.convention(extension.licenceFile.map {
            "${it.asFile.name}_${manifest.id}-${target.loader}"
        })
        extension.metadataProperties.putAll(mapOf(
            "version" to project.version.toString(),
            "minecraft_version" to target.minecraft,
            "loader_version" to pins.loaderVersion,
            "java_version" to pins.java.toString(),
        ))

        project.extensions.getByType(BasePluginExtension::class.java).archivesName
            .convention(extension.archiveName)
        project.repositories.mavenCentral()

        val adapter = when (target.loader) {
            "fabric" -> FabricAdapter()
            "neoforge" -> NeoForgeAdapter()
            else -> error("Unsupported loader ${target.loader}")
        }
        adapter.configure(project, manifest, target, pins)
        TargetResources.configure(project, extension, target.loader)

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.release.set(pins.java)
        }
    }
}
