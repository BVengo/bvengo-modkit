package dev.bvengo.modkit

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Exercises the shared update configuration before any real consumer is migrated. */
@Tag("functional")
internal class FoundationFunctionalTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var directory: Path

    @Test
    fun `central dependencies compile in their intended roots and expand requested override pins`() {
        val fixture = BootstrapFixture(directory)
        fixture.create(kotlin = false, composite = false)
        fixture.write("mod.toml", """
            schema = 1
            [mod]
            id = "fixture"
            group = "dev.bvengo.fixture"
            version = "1.0.0"
            [targets]
            minecraft = ["26.3"]
            loaders = ["fabric"]
            vcs = "26.3-fabric"
            [dependencies]
            fabric_api = true
            modmenu = true
            annotations = true
            yacl = true
            [overrides.targets."26.3.fabric".dependencies]
            fabric_api = "0.153.2+26.3"
        """)
        fixture.write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)
        fixture.write("src/main/java/fixture/Dependencies.java", """
            package fixture;
            public final class Dependencies {
                public net.fabricmc.fabric.api.event.Event<Runnable> event;
                @org.jetbrains.annotations.Nullable
                public Object value;
            }
        """)
        fixture.write("src/client/java/fixture/ClientDependencies.java", """
            package fixture;
            public final class ClientDependencies {
                public com.terraformersmc.modmenu.api.ModMenuApi menu;
                public dev.isxander.yacl3.api.YetAnotherConfigLib config;
            }
        """)
        fixture.write("src/main/resources/fabric.mod.json", """
            {"schemaVersion":1,"id":"fixture","version":"${'$'}{version}","environment":"client",
             "custom":{"api":"${'$'}{fabric_api_version}","menu":"${'$'}{modmenu_version}",
             "annotations":"${'$'}{annotations_version}","yacl":"${'$'}{yacl_version}"}}
        """)

        val buildFile = directory.resolve("build.gradle")
        Files.writeString(buildFile, Files.readString(buildFile) + """

            def compileGraph = configurations.compileClasspath
            def clientGraph = configurations.clientCompileClasspath
            def runtimeGraph = configurations.clientRuntimeClasspath
            def report = layout.buildDirectory.file("dependency-graph.txt")
            tasks.register("writeDependencyGraph") {
                inputs.files(compileGraph, clientGraph, runtimeGraph)
                outputs.file(report)
                doLast {
                    report.get().asFile.text = [
                        "main=" + compileGraph.files.collect { it.name }.sort().join(","),
                        "client=" + clientGraph.files.collect { it.name }.sort().join(","),
                        "runtime=" + runtimeGraph.files.collect { it.name }.sort().join(",")
                    ].join("\n")
                }
            }
        """.trimIndent() + "\n")

        fixture.run("buildAll", ":26.3-fabric:writeDependencyGraph")
        val graph = Files.readAllLines(directory.resolve("versions/26.3-fabric/build/dependency-graph.txt"))
        assertTrue(graph[0].contains("fabric-api-0.153.2+26.3.jar"), graph.toString())
        assertTrue(graph[0].contains("annotations-26.1.0.jar"), graph.toString())
        assertFalse(graph[0].contains("modmenu-"), graph.toString())
        assertFalse(graph[0].contains("yet-another-config-lib-"), graph.toString())
        assertTrue(graph[1].contains("modmenu-21.0.0-beta.1.jar"), graph.toString())
        assertTrue(graph[1].contains("yet-another-config-lib-3.9.7+26.3-fabric.jar"), graph.toString())
        assertFalse(graph[2].contains("annotations-26.1.0.jar"), graph.toString())

        val binary = directory.resolve("build/libs/fixture-fabric-1.0.0-mc26.3.jar")
        ZipFile(binary.toFile()).use { jar ->
            val descriptor = jar.getInputStream(jar.getEntry("fabric.mod.json")).bufferedReader().use { it.readText() }
            listOf("0.153.2+26.3", "21.0.0-beta.1", "26.1.0", "3.9.7+26.3-fabric").forEach { pin ->
                assertTrue(descriptor.contains(pin), descriptor)
            }
            assertFalse(descriptor.contains("${'$'}{"), descriptor)
        }

        fixture.run("buildAll", "--configuration-cache")
        val reused = fixture.run("buildAll", "--configuration-cache")
        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
    }

    @Test
    fun `all six recorded targets build and collection drops removed targets`() {
        val fixture = BootstrapFixture(directory)
        fixture.create(kotlin = false, composite = false)
        val manifestPath = directory.resolve("mod.toml")
        val manifest = Files.readString(manifestPath).replace("[\"26.3\"]", "[\"26.1\", \"26.2\", \"26.3\"]") + """

            [dependencies]
            fabric_api = true
            modmenu = true
            annotations = true
        """.trimIndent() + "\n"
        Files.writeString(manifestPath, manifest)
        fixture.write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)
        fixture.write("src/main/java/fixture/Annotated.java", """
            package fixture;
            public final class Annotated {
                @org.jetbrains.annotations.Nullable
                public Object value;
            }
        """)

        val built = fixture.run("buildAll")
        listOf("26.1", "26.2", "26.3").forEach { minecraft ->
            listOf("fabric", "neoforge").forEach { loader ->
                assertEquals(TaskOutcome.SUCCESS, built.task(":$minecraft-$loader:jar")?.outcome)
            }
            assertTrue(built.output.contains("$minecraft-neoforge: skipping Fabric-only dependency modmenu"))
        }
        assertEquals(12, collectedJars().size)

        // A direct target build must not require root collection or all other target builds.
        val targetBuild = fixture.run(":26.3-fabric:build")
        assertEquals(null, targetBuild.task(":collectArtifacts"))

        Files.writeString(manifestPath, manifest.replace("[\"26.1\", \"26.2\", \"26.3\"]", "[\"26.3\"]"))
        fixture.run("buildAll", "--configuration-cache")
        val names = collectedJars()
        assertEquals(4, names.size)
        assertTrue(names.all { it.contains("mc26.3") }, names.toString())

        val reused = fixture.run("buildAll", "--configuration-cache")
        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
        assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":collectArtifacts")?.outcome)
    }

    private fun collectedJars(): List<String> {
        return Files.list(directory.resolve("build/libs")).use { files ->
            files.filter { it.fileName.toString().endsWith(".jar") }.map { it.fileName.toString() }.sorted().toList()
        }
    }
}
