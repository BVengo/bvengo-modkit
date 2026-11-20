package dev.bvengo.modkit.platform

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.TargetPins
import dev.bvengo.modkit.tasks.CheckCommonSide
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar

/** Wires additional sources through ModDevGradle's public source-set and run APIs. */
internal class NeoForgeAdapter : LoaderAdapter {
    override fun configure(project: Project, manifest: ModManifest, target: Target, pins: TargetPins) {
        project.pluginManager.apply("net.neoforged.moddev")

        val sources = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sources.getByName("main")
        val client = sources.create("client")

        listOf("Implementation", "CompileOnly", "RuntimeOnly").forEach { scope ->
            val commonName = scope.replaceFirstChar(Char::lowercaseChar)
            project.configurations.named("client$scope") {
                extendsFrom(project.configurations.getByName(commonName))
            }
        }
        project.dependencies.add(client.implementationConfigurationName, main.output)

        val neoForge = project.extensions.getByType(NeoForgeExtension::class.java)
        neoForge.version = pins.loaderVersion
        neoForge.addModdingDependenciesTo(client)
        neoForge.mods.register(manifest.id) {
            sourceSet(main)
            sourceSet(client)
        }
        neoForge.runs.register("client") {
            client()
            sourceSet.set(client)
            gameDirectory.set(project.rootProject.layout.projectDirectory.dir("run/neoforge"))
        }

        project.tasks.named("createMinecraftArtifacts") {
            // ModDevGradle discovers default access transformers in main resources.
            dependsOn("stonecutterGenerate")
        }
        project.tasks.named("jar", Jar::class.java) {
            from(client.output)
        }
        project.tasks.named("sourcesJar", Jar::class.java) {
            from(client.allSource)
        }

        val checkCommonSide = project.tasks.register("checkCommonSide", CheckCommonSide::class.java) {
            group = "verification"
            description = "Rejects known physical-client namespace dependencies in common output."
            classesDirectories.from(main.output.classesDirs)
        }
        project.tasks.named("check") {
            dependsOn(checkCommonSide)
        }

        project.logger.warn(
            "ModKit ${target.projectName}: NeoForge main compilation includes client game classes. " +
                "checkCommonSide checks known client namespaces. Side annotations, dependency closures " +
                "and runtime behaviour require separate validation.",
        )
    }
}
