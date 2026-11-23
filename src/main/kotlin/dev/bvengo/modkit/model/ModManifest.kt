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
    val dependencyLoaders: Map<String, Set<String>> = emptyMap(),
    val metadata: ModMetadata? = null,
    val packaging: Packaging = Packaging(),
    val mavenPublication: Boolean = false,
    val compatibility: Map<String, Map<String, String>> = emptyMap(),
)

/** Consumer-owned identity and loader relationships for generated descriptors. */
data class ModMetadata(
    val name: String,
    val description: String,
    val licence: String,
    val environment: String,
    val authors: List<String>,
    val contributors: List<String>,
    val icon: String?,
    val contact: Map<String, String>,
    val entrypoints: Map<String, List<String>>,
    val fabricDepends: Map<String, String>,
    val fabricRecommends: Map<String, String>,
    val fabricCustom: Map<String, Any>,
    val mixins: MixinMetadata?,
)

/** One mod-owned mixin configuration, shared between loader descriptors. */
data class MixinMetadata(
    val packageName: String,
    val common: List<String>,
    val client: List<String>,
    val required: Boolean,
    val defaultRequire: Int,
)

/** Archive options remain overridable through the native Gradle extension. */
data class Packaging(val licenceFile: String? = null, val licenceEntry: String? = null)

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
