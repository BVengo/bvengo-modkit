package dev.bvengo.modkit.platform

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.TargetPins
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer

/** Uses Loom's native non-remapping split-source implementation. */
internal class FabricAdapter : LoaderAdapter {
    override fun configure(project: Project, manifest: ModManifest, target: Target, pins: TargetPins) {
        project.pluginManager.apply("net.fabricmc.fabric-loom")

        val loom = project.extensions.getByType(LoomGradleExtensionAPI::class.java)
        loom.splitEnvironmentSourceSets()

        val sources = project.extensions.getByType(SourceSetContainer::class.java)
        loom.mods.register(manifest.id) {
            sourceSet(sources.getByName("main"))
            sourceSet(sources.getByName("client"))
        }
        loom.runConfigs.configureEach {
            runDirectory.set(project.rootProject.layout.projectDirectory.dir("run/fabric").asFile)
        }

        project.dependencies.add("minecraft", "com.mojang:minecraft:${target.minecraft}")
        project.dependencies.add("implementation", "net.fabricmc:fabric-loader:${pins.loaderVersion}")
    }
}
