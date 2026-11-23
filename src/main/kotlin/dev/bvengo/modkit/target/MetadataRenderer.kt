package dev.bvengo.modkit.target

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.ModMetadata
import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.TargetPins
import groovy.json.JsonOutput

/** Produces loader-native descriptors from the consumer's explicit declarations. */
internal object MetadataRenderer {
    fun render(manifest: ModManifest, target: Target, pins: TargetPins, values: Map<String, String>): Map<String, String> {
        val metadata = requireNotNull(manifest.metadata)
        val files = linkedMapOf<String, String>()
        val descriptor = if (target.loader == "fabric") "fabric.mod.json" else "META-INF/neoforge.mods.toml"
        files[descriptor] = if (target.loader == "fabric") {
            fabric(manifest, metadata, values)
        } else {
            neoForge(manifest, metadata, pins, values)
        }

        metadata.mixins?.let { mixins ->
            files["${manifest.id}.mixins.json"] = json(linkedMapOf(
                "required" to mixins.required,
                "package" to mixins.packageName,
                "compatibilityLevel" to "JAVA_${values.getValue("java_version")}",
                "mixins" to mixins.common,
                "client" to mixins.client,
                "injectors" to mapOf("defaultRequire" to mixins.defaultRequire),
            ))
        }
        return files
    }

    private fun fabric(manifest: ModManifest, metadata: ModMetadata, values: Map<String, String>): String {
        val depends = linkedMapOf(
            "fabricloader" to ">=${values.getValue("loader_version")}",
            "minecraft" to values.getValue("minecraft_range"),
            "java" to ">=${values.getValue("java_version")}",
        ) + metadata.fabricDepends
        val descriptor = linkedMapOf<String, Any>(
            "schemaVersion" to 1, "id" to manifest.id, "version" to values.getValue("version"),
            "name" to metadata.name, "description" to metadata.description,
            "authors" to metadata.authors, "license" to metadata.licence,
            "environment" to metadata.environment, "depends" to depends,
        )
        metadata.icon?.let { descriptor["icon"] = it }
        if (metadata.contributors.isNotEmpty()) {
            descriptor["contributors"] = metadata.contributors
        }
        if (metadata.contact.isNotEmpty()) {
            descriptor["contact"] = metadata.contact
        }
        if (metadata.entrypoints.isNotEmpty()) {
            descriptor["entrypoints"] = metadata.entrypoints
        }
        if (metadata.mixins != null) {
            descriptor["mixins"] = listOf("${manifest.id}.mixins.json")
        }
        if (metadata.fabricRecommends.isNotEmpty()) {
            descriptor["recommends"] = metadata.fabricRecommends
        }
        if (metadata.fabricCustom.isNotEmpty()) {
            descriptor["custom"] = metadata.fabricCustom
        }

        return json(descriptor)
    }

    private fun neoForge(
        manifest: ModManifest, metadata: ModMetadata, pins: TargetPins, values: Map<String, String>,
    ): String {
        val side = if (metadata.environment == "client") "CLIENT" else "BOTH"
        return buildString {
            appendLine("modLoader = \"javafml\"")
            appendLine("loaderVersion = ${quote(requireNotNull(pins.fmlRange))}")
            appendLine("license = ${quote(metadata.licence)}")
            appendLine()
            appendLine("[[mods]]")
            appendLine("modId = ${quote(manifest.id)}")
            appendLine("version = ${quote(values.getValue("version"))}")
            appendLine("displayName = ${quote(metadata.name)}")
            appendLine("description = ${quote(metadata.description)}")
            metadata.icon?.let { appendLine("iconFile = ${quote(it)}") }
            if (metadata.authors.isNotEmpty()) {
                appendLine("authors = ${quote(metadata.authors.joinToString(", "))}")
            }
            if (metadata.mixins != null) {
                appendLine()
                appendLine("[[mixins]]")
                appendLine("config = ${quote("${manifest.id}.mixins.json")}")
            }
            dependency(manifest.id, "neoforge", "[${values.getValue("loader_version")},)", side)
            dependency(manifest.id, "minecraft", values.getValue("minecraft_range"), side)
        }
    }

    private fun StringBuilder.dependency(modId: String, dependency: String, range: String, side: String) {
        appendLine()
        appendLine("[[dependencies.$modId]]")
        appendLine("modId = ${quote(dependency)}")
        appendLine("type = \"required\"")
        appendLine("versionRange = ${quote(range)}")
        appendLine("ordering = \"NONE\"")
        appendLine("side = ${quote(side)}")
    }

    private fun json(value: Map<String, Any>): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            append(when (character) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (character.code < 32 || character.code == 127) "\\u%04x".format(character.code)
                    else character.toString()
            })
        }
        append('"')
    }
}
