package dev.bvengo.modkit.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Writes only generated resources and rejects competing hand-authored descriptors. */
@CacheableTask
abstract class GenerateModMetadata : DefaultTask() {
    @get:Input
    abstract val descriptors: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val existingDescriptors: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        require(existingDescriptors.isEmpty) {
            "Generated metadata conflicts with hand-authored resources: ${existingDescriptors.files.sorted()}. " +
                "Remove those descriptors or remove [metadata] from mod.toml"
        }
        val destination = outputDirectory.get().asFile
        destination.deleteRecursively()
        destination.mkdirs()
        descriptors.get().forEach { (path, contents) ->
            val file = destination.resolve(path)
            file.parentFile.mkdirs()
            file.writeText(contents, Charsets.UTF_8)
        }
    }
}
