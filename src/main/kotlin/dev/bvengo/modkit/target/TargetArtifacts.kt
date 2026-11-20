package dev.bvengo.modkit.target

import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar

/** Exposes each target's native archives for root collection without resolving filenames. */
internal object TargetArtifacts {
    fun configure(project: Project) {
        val artifacts = project.configurations.create("modkitArtifacts") {
            isCanBeConsumed = true
            isCanBeResolved = false
            isTransitive = false
        }
        listOf("jar", "sourcesJar").forEach { taskName ->
            artifacts.outgoing.artifact(project.tasks.named(taskName, Jar::class.java))
        }
    }
}
