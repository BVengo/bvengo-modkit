package dev.bvengo.modkit.target

import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

/** Shares resource handling between main and client without expanding arbitrary assets. */
internal object TargetResources {
    fun configure(project: Project, extension: ModKitTargetExtension, loader: String) {
        val properties = extension.metadataProperties
        val excludedDescriptor = when (loader) {
            "fabric" -> "META-INF/neoforge.mods.toml"
            "neoforge" -> "fabric.mod.json"
            else -> error("Unsupported loader $loader")
        }

        project.tasks.withType(ProcessResources::class.java).configureEach {
            inputs.property("modkitMetadata", properties)
            exclude(excludedDescriptor)
            filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml")) {
                expand(properties.get())
            }
        }

        val licenceFile = extension.licenceFile
        val licenceEntry = extension.licenceEntry
        val licenceSources = licenceFile.map { listOf(it) }.orElse(emptyList())

        project.tasks.withType(Jar::class.java).configureEach {
            // A configured missing file must fail instead of producing an unlicensed artifact.
            inputs.file(licenceFile).withPropertyName("modkitLicence")
                .withPathSensitivity(PathSensitivity.NONE).optional()
            inputs.property("modkitLicenceEntry", licenceEntry).optional(true)

            from(licenceSources) {
                rename { licenceEntry.get() }
            }
        }
    }
}
