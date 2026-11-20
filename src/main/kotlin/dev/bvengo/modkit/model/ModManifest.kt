package dev.bvengo.modkit.model

/** Settings-time declarations shared by the three plugin entrypoints. */
data class ModManifest(
    val id: String,
    val group: String,
    val version: String,
    val targets: List<Target>,
    val vcs: String,
    val dependencies: Map<String, Boolean> = emptyMap(),
    val dependencyOverrides: DependencyOverrides = DependencyOverrides(),
)

/** Version overrides never enable a dependency or change its platform applicability. */
data class DependencyOverrides(
    val global: Map<String, String> = emptyMap(),
    val minecraft: Map<String, Map<String, String>> = emptyMap(),
    val targets: Map<String, Map<String, String>> = emptyMap(),
)

/** An explicit target, independent of punctuation in Minecraft version identifiers. */
data class Target(val minecraft: String, val loader: String) {
    /** Stonecutter's generated project identifier. */
    val projectName: String get() = "$minecraft-$loader"
}
