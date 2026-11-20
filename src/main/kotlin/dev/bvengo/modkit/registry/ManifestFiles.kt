package dev.bvengo.modkit.registry

import dev.bvengo.modkit.model.ModManifest
import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.ProviderFactory
import java.io.File

/** Tracks manifest reads through Gradle's provider API. */
internal fun readManifest(providers: ProviderFactory, layout: ProjectLayout, root: File): ModManifest {
    val file = File(root, "mod.toml")
    val location = layout.file(providers.provider { file })
    val contents = providers.fileContents(location).asText.get()

    return ManifestReader.read(contents, file.path)
}
