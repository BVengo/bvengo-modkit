package dev.bvengo.modkit.target

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/** Project-time overrides for resources and archives on a generated target. */
abstract class ModKitTargetExtension {
    /** Archive base name, excluding the target version and classifier. */
    abstract val archiveName: Property<String>

    /** Explicit licence input. No licence is selected or copied when this is unset. */
    abstract val licenceFile: RegularFileProperty

    /** Path of the licence entry in binary and sources archives. */
    abstract val licenceEntry: Property<String>

    /** Expansion values used only in loader descriptor templates. */
    abstract val metadataProperties: MapProperty<String, String>
}
