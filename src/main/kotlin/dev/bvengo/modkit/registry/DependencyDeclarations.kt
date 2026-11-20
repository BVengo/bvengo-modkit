package dev.bvengo.modkit.registry

import dev.bvengo.modkit.model.DependencyOverrides
import dev.bvengo.modkit.model.Target
import org.tomlj.TomlTable

/** Validates the small dependency schema independently of Gradle configuration. */
internal object DependencyDeclarations {
    fun readDependencies(table: TomlTable?): Map<String, Boolean> {
        if (table == null) {
            return emptyMap()
        }
        checkKeys(table, DependencyRegistry.definitions.keys, "dependencies")

        return table.keySet().associateWith { key -> table.getBoolean(listOf(key))!! }
    }

    fun readOverrides(
        table: TomlTable?,
        targets: List<Target>,
        dependencies: Map<String, Boolean>,
    ): DependencyOverrides {
        if (table == null) {
            return DependencyOverrides()
        }
        checkKeys(table, setOf("dependencies", "minecraft", "targets"), "overrides")

        val global = versions(table.getTable("dependencies"), dependencies, "overrides.dependencies")
        val minecraft = scopedVersions(
            table.getTable("minecraft"), targets.map { it.minecraft }.toSet(),
            dependencies, "overrides.minecraft",
        )
        val targetNames = targets.map { "${it.minecraft}.${it.loader}" }.toSet()
        val targetOverrides = scopedVersions(
            table.getTable("targets"), targetNames, dependencies, "overrides.targets",
        )

        return DependencyOverrides(global, minecraft, targetOverrides)
    }

    private fun scopedVersions(
        table: TomlTable?,
        allowed: Set<String>,
        dependencies: Map<String, Boolean>,
        path: String,
    ): Map<String, Map<String, String>> {
        if (table == null) {
            return emptyMap()
        }
        checkKeys(table, allowed, path)

        return table.keySet().associateWith { key ->
            val scope = requireNotNull(table.getTable(listOf(key))) { "$path.$key must be a table" }
            checkKeys(scope, setOf("dependencies"), "$path.$key")
            versions(scope.getTable("dependencies"), dependencies, "$path.$key.dependencies")
        }
    }

    private fun versions(
        table: TomlTable?,
        dependencies: Map<String, Boolean>,
        path: String,
    ): Map<String, String> {
        if (table == null) {
            return emptyMap()
        }
        checkKeys(table, DependencyRegistry.definitions.keys, path)

        return table.keySet().associateWith { key ->
            require(dependencies[key] == true) { "$path.$key overrides a disabled dependency" }
            val version = requireNotNull(table.getString(listOf(key))) { "$path.$key needs a version" }
            requirePinnedVersion(version, "$path.$key")
            version
        }
    }

    private fun checkKeys(table: TomlTable, allowed: Set<String>, path: String) {
        val unknown = table.keySet() - allowed
        require(unknown.isEmpty()) { "$path contains unsupported keys: ${unknown.sorted().joinToString()}" }
    }
}

/** Allows literal Minecraft version suffixes while rejecting Gradle version selectors. */
internal fun requirePinnedVersion(version: String, path: String) {
    require(
        version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]*")) &&
            !version.endsWith("+") && !version.startsWith("latest.") && !version.endsWith("-SNAPSHOT"),
    ) { "$path must use a pinned release version, got $version" }
}
