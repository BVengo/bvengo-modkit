package dev.bvengo.modkit.settings

import dev.bvengo.modkit.registry.ManifestReader
import dev.kikugie.stonecutter.settings.StonecutterSettingsExtension
import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.JavaVersion

/** Selects explicit targets before Stonecutter creates their projects. */
class ModKitSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        require(settings.gradle.gradleVersion == "9.6.0" && JavaVersion.current() == JavaVersion.VERSION_25) {
            "ModKit's bootstrap cohort requires Gradle 9.6.0 and Java 25"
        }

        val file = settings.layout.settingsDirectory.file("mod.toml")
        val manifest = ManifestReader.read(settings.providers.fileContents(file).asText.get(), file.asFile.path)

        settings.pluginManager.apply("dev.kikugie.stonecutter")
        val stonecutter = settings.extensions.getByType(StonecutterSettingsExtension::class.java)
        stonecutter.create(settings.rootProject) {
            manifest.targets.forEach { target -> version(target.projectName, target.minecraft) }
            vcsVersion.set(manifest.vcs)
        }
    }
}
