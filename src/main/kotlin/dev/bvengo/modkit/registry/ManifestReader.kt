package dev.bvengo.modkit.registry

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target
import org.gradle.api.GradleException
import org.tomlj.Toml
import org.tomlj.TomlInvalidTypeException
import org.tomlj.TomlTable

/** Parses explicit targets and dependency declarations before project creation. */
object ManifestReader {
    /** Reads a manifest with a source name retained in every validation diagnostic. */
    fun read(text: String, source: String): ModManifest {
        try {
            val document = Toml.parse(text)
            require(!document.hasErrors()) { document.errors().joinToString("\n") }
            document.checkKeys(setOf(
                "schema", "mod", "targets", "dependencies", "overrides",
                "metadata", "packaging", "publishing", "compatibility",
            ), "manifest")
            require(document.getLong("schema") == 1L) { "schema must be 1" }

            val mod = requireNotNull(document.getTable("mod")) { "missing [mod]" }
            mod.checkKeys(setOf("id", "group", "version"), "mod")
            val id = mod.requiredString("id")
            require(id.matches(Regex("[a-z][a-z0-9_]{1,63}"))) { "mod.id is not a valid loader identifier" }

            val selection = requireNotNull(document.getTable("targets")) { "missing [targets]" }
            selection.checkKeys(setOf("minecraft", "loaders", "vcs"), "targets")
            val versions = selection.requiredStrings("minecraft")
            val loaders = selection.requiredStrings("loaders")
            require(loaders.all { it in setOf("fabric", "neoforge") }) {
                "targets.loaders supports fabric and neoforge only"
            }

            val targets = versions.flatMap { minecraft -> loaders.map { Target(minecraft, it) } }
            targets.forEach { TargetRegistry.load(it) }

            val vcs = selection.requiredString("vcs")
            require(targets.any { it.projectName == vcs }) { "targets.vcs must name a declared target" }

            val dependencies = DependencyDeclarations.readDependencies(document.getTable("dependencies"))
            val overrides = DependencyDeclarations.readOverrides(document.getTable("overrides"), targets, dependencies)
            val manifest = ModManifest(
                id, mod.requiredString("group"), mod.requiredString("version"), targets, vcs,
                dependencies, overrides,
                dependencyLoaders = DependencyDeclarations.readLoaders(document.getTable("dependencies"), targets),
                metadata = ManifestOptions.metadata(document.getTable("metadata")),
                packaging = ManifestOptions.packaging(document.getTable("packaging")),
                mavenPublication = ManifestOptions.publication(document.getTable("publishing")),
                compatibility = ManifestOptions.compatibility(document.getTable("compatibility"), targets),
            )
            targets.forEach { DependencyRegistry.resolve(manifest, it) }

            return manifest
        } catch (exception: TomlInvalidTypeException) {
            throw GradleException("$source: ${exception.message}", exception)
        } catch (exception: IllegalArgumentException) {
            throw GradleException("$source: ${exception.message}", exception)
        }
    }

    private fun TomlTable.requiredString(key: String): String {
        return requireNotNull(getString(key)) { "missing $key" }.also {
            require(it.isNotBlank()) { "$key must not be blank" }
        }
    }

    private fun TomlTable.requiredStrings(key: String): List<String> {
        val array = requireNotNull(getArray(key)) { "missing targets.$key" }
        require(array.toList().all { it is String }) { "targets.$key must contain strings" }
        val values = (0 until array.size()).map { array.getString(it) }
        require(values.isNotEmpty() && values.none(String::isBlank)) { "targets.$key must not be empty" }
        require(values.distinct().size == values.size) { "targets.$key contains duplicates" }

        return values
    }
}
