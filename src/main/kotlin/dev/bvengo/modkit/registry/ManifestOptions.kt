package dev.bvengo.modkit.registry

import dev.bvengo.modkit.model.MixinMetadata
import dev.bvengo.modkit.model.ModMetadata
import dev.bvengo.modkit.model.Packaging
import dev.bvengo.modkit.model.Target
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.nio.file.Path

/** Parses consumer metadata without coupling the manifest to Gradle project objects. */
internal object ManifestOptions {
    fun metadata(table: TomlTable?): ModMetadata? {
        if (table == null) {
            return null
        }
        table.checkKeys(setOf(
            "name", "description", "licence", "environment", "authors", "contributors", "icon",
            "contact", "entrypoints", "fabric", "mixins",
        ), "metadata")

        val fabric = table.getTable("fabric")
        fabric?.checkKeys(setOf("depends", "recommends", "custom"), "metadata.fabric")
        val environment = table.requiredText("environment")
        require(environment in setOf("client", "*")) { "metadata.environment must be client or *" }
        val entrypoints = table.getTable("entrypoints")
        val entrypointValues = entrypoints?.keySet()?.associateWith { key ->
            entrypoints.texts(key).also {
                require(it.isNotEmpty()) { "metadata.entrypoints.$key must not be empty" }
            }
        }.orEmpty()

        return ModMetadata(
            name = table.requiredText("name"),
            description = table.requiredText("description"),
            licence = table.requiredText("licence"),
            environment = environment,
            authors = table.texts("authors"),
            contributors = table.texts("contributors"),
            icon = table.getString("icon")?.also { relativePath(it, "metadata.icon") },
            contact = table.getTable("contact").stringMap(),
            entrypoints = entrypointValues,
            fabricDepends = fabric?.getTable("depends").stringMap(),
            fabricRecommends = fabric?.getTable("recommends").stringMap(),
            fabricCustom = fabric?.getTable("custom")?.let(::objectValues).orEmpty(),
            mixins = mixins(table.getTable("mixins")),
        )
    }

    fun packaging(table: TomlTable?): Packaging {
        table?.checkKeys(setOf("licence_file", "licence_entry"), "packaging")
        val file = table?.getString("licence_file")
        val entry = table?.getString("licence_entry")
        file?.let { relativePath(it, "packaging.licence_file") }
        entry?.let { relativePath(it, "packaging.licence_entry") }
        require(entry == null || file != null) { "packaging.licence_entry requires licence_file" }

        return Packaging(file, entry)
    }

    fun publication(table: TomlTable?): Boolean {
        table?.checkKeys(setOf("maven"), "publishing")
        return table?.getBoolean("maven") ?: false
    }

    fun compatibility(table: TomlTable?, targets: List<Target>): Map<String, Map<String, String>> {
        if (table == null) {
            return emptyMap()
        }
        table.checkKeys(targets.map { it.minecraft }.toSet(), "compatibility")

        return table.keySet().associateWith { minecraft ->
            val versions = requireNotNull(table.getTable(listOf(minecraft))) {
                "compatibility.$minecraft must be a table"
            }
            versions.checkKeys(targets.filter { it.minecraft == minecraft }.map { it.loader }.toSet(),
                "compatibility.$minecraft")
            versions.stringMap()
        }
    }

    private fun mixins(table: TomlTable?): MixinMetadata? {
        if (table == null) {
            return null
        }
        table.checkKeys(setOf("package", "common", "client", "required", "default_require"), "metadata.mixins")
        val common = table.texts("common")
        val client = table.texts("client")
        require((common + client).isNotEmpty()) { "metadata.mixins needs common or client classes" }
        require((common + client).distinct().size == common.size + client.size) {
            "metadata.mixins contains duplicate classes"
        }
        val defaultRequire = table.getLong("default_require") ?: 1
        require(defaultRequire in 0..Int.MAX_VALUE.toLong()) { "metadata.mixins.default_require must be nonnegative" }

        return MixinMetadata(table.requiredText("package"), common, client,
            table.getBoolean("required") ?: true, defaultRequire.toInt())
    }

    private fun relativePath(value: String, name: String) {
        val path = Path.of(value)
        require(value.isNotBlank() && path.root == null && !path.normalize().startsWith("..") &&
            '\\' !in value && ':' !in value) { "$name must be a relative path inside the mod" }
    }

    private fun objectValues(table: TomlTable): Map<String, Any> {
        return table.keySet().associateWith { key -> value(requireNotNull(table.get(listOf(key)))) }
    }

    private fun value(value: Any): Any {
        return when (value) {
            is TomlTable -> objectValues(value)
            is TomlArray -> value.toList().map(::value)
            is String, is Boolean, is Long -> value
            is Double -> value.also { require(it.isFinite()) { "metadata custom numbers must be finite" } }
            else -> throw IllegalArgumentException("metadata custom values must be JSON-compatible, found ${value.javaClass.simpleName}")
        }
    }
}

internal fun TomlTable.checkKeys(allowed: Set<String>, path: String) {
    val unknown = keySet() - allowed
    require(unknown.isEmpty()) { "$path contains unsupported keys: ${unknown.sorted().joinToString()}" }
}

internal fun TomlTable.requiredText(key: String): String {
    return requireNotNull(getString(listOf(key))) { "missing $key" }.also {
        require(it.isNotBlank()) { "$key must not be blank" }
    }
}

internal fun TomlTable.texts(key: String): List<String> {
    val array = getArray(listOf(key)) ?: return emptyList()
    require(array.toList().all { it is String && it.isNotBlank() }) { "$key must contain nonblank strings" }
    return array.toList().map { it as String }
}

internal fun TomlTable?.stringMap(): Map<String, String> {
    return this?.keySet()?.associateWith { requiredText(it) }.orEmpty()
}
