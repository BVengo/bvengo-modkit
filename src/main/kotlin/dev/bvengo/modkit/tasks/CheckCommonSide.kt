package dev.bvengo.modkit.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider

/** Checks common output for dependencies on known physical-client namespaces using JDK jdeps. */
@DisableCachingByDefault(because = "Produces verification diagnostics without output files")
abstract class CheckCommonSide : DefaultTask() {
    /** Compiled common output roots, preserving their compilation dependencies. */
    @get:Internal
    abstract val classesDirectories: ConfigurableFileCollection

    /** Tracks class contents and permits client-only mods with empty common output. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val classFiles: FileTree
        get() = classesDirectories.asFileTree.matching { include("**/*.class") }

    /** Fails when analysis fails or reports any dependency matching the client namespace policy. */
    @TaskAction
    fun checkDependencies() {
        val checker = ToolProvider.findFirst("jdeps").orElseThrow {
            GradleException("ModKit common-side validation requires a full Java 25 JDK with jdeps")
        }
        val output = StringWriter()
        val errors = StringWriter()
        val arguments = listOf(
            "-verbose:class", "-filter:none", "-regex",
            "net\\.minecraft\\.client\\..*|com\\.mojang\\.blaze3d\\..*|" +
                "com\\.mojang\\.realmsclient\\..*|net\\.neoforged\\.neoforge\\.client\\..*",
        ) + classesDirectories.files.filter { it.exists() }.map { it.absolutePath }

        val result = checker.run(PrintWriter(output), PrintWriter(errors), *arguments.toTypedArray())
        if (result != 0 || errors.toString().isNotBlank()) {
            throw GradleException("ModKit jdeps analysis failed ($result):\n$errors$output")
        }
        if (output.toString().isNotBlank()) {
            throw GradleException("Forbidden physical-client dependencies in common output:\n$output")
        }
    }
}
