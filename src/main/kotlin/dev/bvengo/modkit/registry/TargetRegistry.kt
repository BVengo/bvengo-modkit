package dev.bvengo.modkit.registry

import dev.bvengo.modkit.model.Target
import org.tomlj.Toml

/** Exact bootstrap pins shipped inside the implementation artifact. */
data class TargetPins(
    val java: Int,
    val loaderVersion: String,
    val dependencies: Map<String, String>,
    val minecraftRange: String,
    val fmlRange: String?,
)

/** Reads only explicitly available targets, without guessing compatible versions. */
object TargetRegistry {
    /** Fails before project creation when a requested target has no embedded pin. */
    fun load(target: Target): TargetPins {
        require(target.minecraft.matches(Regex("[A-Za-z0-9.+_-]+"))) { "invalid Minecraft target ${target.minecraft}" }
        val resource = "/dev/bvengo/modkit/registry/minecraft/${target.minecraft}.toml"
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) {
            "unsupported target ${target.projectName}: no embedded registry entry"
        }

        val registry = stream.use { Toml.parse(it) }
        require(!registry.hasErrors()) { "invalid embedded registry $resource" }
        val loader = requireNotNull(registry.getString("${target.loader}.loader")) {
            "unsupported loader ${target.loader} for Minecraft ${target.minecraft}"
        }

        val dependencies = listOf("common.dependencies", "${target.loader}.dependencies")
            .mapNotNull(registry::getTable)
            .flatMap { table -> table.keySet().map { key -> key to requireNotNull(table.getString(listOf(key))) } }
            .toMap()

        requirePinnedVersion(loader, "$resource ${target.loader}.loader")

        val minecraftRange = requireNotNull(registry.getString("${target.loader}.minecraft_range")) {
            "missing Minecraft compatibility range in $resource for ${target.loader}"
        }
        return TargetPins(
            Math.toIntExact(requireNotNull(registry.getLong("minecraft.java"))), loader, dependencies,
            minecraftRange, registry.getString("${target.loader}.fml_range"),
        )
    }
}
