package dev.bvengo.modkit.target

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

/** Opts into native Maven publication without configuring a publication destination. */
internal object TargetPublication {
    fun configure(project: Project, extension: ModKitTargetExtension) {
        project.pluginManager.apply("maven-publish")

        // The combined artifact exposes the dependencies needed by its packaged client output.
        project.configurations.getByName("runtimeOnly")
            .extendsFrom(project.configurations.getByName("clientImplementation"))
        project.extensions.getByType(PublishingExtension::class.java).publications
            .create("mavenJava", MavenPublication::class.java) {
                artifactId = extension.archiveName.get()
                from(project.components.getByName("java"))
            }
    }
}
