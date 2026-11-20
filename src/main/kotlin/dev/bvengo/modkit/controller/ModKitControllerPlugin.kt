package dev.bvengo.modkit.controller

import dev.bvengo.modkit.registry.readManifest
import dev.kikugie.stonecutter.controller.StonecutterControllerExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete

/** Configures shared preprocessing while leaving the active literal in the consumer script. */
class ModKitControllerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val manifest = readManifest(project.providers, project.layout, project.rootDir)
        project.pluginManager.apply("dev.kikugie.stonecutter")

        val stonecutter = project.extensions.getByType(StonecutterControllerExtension::class.java)
        stonecutter.parameters {
            val target = manifest.targets.single { it.projectName == current.project }
            constants { match(target.loader, "fabric", "neoforge") }
        }

        val build = project.tasks.register("build") {
            group = "build"
            description = "Builds every explicitly declared target."
            dependsOn(manifest.targets.map { ":${it.projectName}:build" })
        }
        project.tasks.register("buildAll") {
            group = "build"
            dependsOn(build)
        }
        project.tasks.register("clean", Delete::class.java) {
            delete(project.layout.buildDirectory)
            dependsOn(manifest.targets.map { ":${it.projectName}:clean" })
        }
    }
}
