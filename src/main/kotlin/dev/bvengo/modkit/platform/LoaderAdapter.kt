package dev.bvengo.modkit.platform

import dev.bvengo.modkit.model.ModManifest
import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.TargetPins
import org.gradle.api.Project

/** Loader-specific configuration behind the shared target plugin. */
internal interface LoaderAdapter {
    fun configure(project: Project, manifest: ModManifest, target: Target, pins: TargetPins)
}
