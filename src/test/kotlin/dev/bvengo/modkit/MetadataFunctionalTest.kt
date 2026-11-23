package dev.bvengo.modkit

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import org.tomlj.Toml
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Exercises generation through Maven consumption and native resource/archive tasks. */
@Tag("functional")
internal class MetadataFunctionalTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var directory: Path

    @Test
    fun `manifest resources and publications build incrementally with configuration cache`() {
        val fixture = BootstrapFixture(directory)
        fixture.create(kotlin = true, composite = false)
        Files.delete(directory.resolve("src/main/resources/fabric.mod.json"))
        val original = Files.readString(directory.resolve("mod.toml")) + """

            [dependencies]
            modmenu = true
            annotations = { loaders = ["neoforge"] }
            [packaging]
            licence_file = "LICENSE.md"
            licence_entry = "LICENSE_fixture"
            [publishing]
            maven = true
            [compatibility."26.3"]
            fabric = "=26.3"
            neoforge = "[26.3]"
            [metadata]
            name = "Fixture"
            description = 'Literal ${'$'}{do_not_expand} and backslash \'
            licence = "All Rights Reserved"
            environment = "client"
            authors = ["Example"]
            [metadata.entrypoints]
            client = ["fixture.ClientOnly"]
            [metadata.fabric.recommends]
            modmenu = "*"
            [metadata.fabric.custom]
            marker = "first"
            [metadata.mixins]
            package = "fixture.mixin"
            client = ["ClientMixin"]
        """.trimIndent()
        fixture.write("mod.toml", original)
        fixture.write("LICENSE.md", "Fixture licence")
        fixture.write("src/client/resources/raw.txt", "${'$'}{untouched}")

        val tasks = arrayOf("buildAll", "generatePomFileForMavenJavaPublication",
            "generateMetadataFileForMavenJavaPublication")
        fixture.run(*tasks)
        assertArchives("first", mixins = true)
        for (loader in listOf("fabric", "neoforge")) {
            val publication = directory.resolve("versions/26.3-$loader/build/publications/mavenJava")
            val pom = Files.readString(publication.resolve("pom-default.xml"))
            assertTrue(pom.contains("<artifactId>fixture-$loader</artifactId>"), pom)
            assertFalse(pom.contains("annotations"), pom)
            if (loader == "fabric") {
                assertTrue(pom.contains("<artifactId>modmenu</artifactId>"), pom)
                assertTrue(pom.contains("<scope>runtime</scope>"), pom)
            }
        }

        fixture.run(*tasks, "--configuration-cache")
        val reused = fixture.run(*tasks, "--configuration-cache")
        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
        for (loader in listOf("fabric", "neoforge")) {
            assertEquals(TaskOutcome.UP_TO_DATE, reused.task(":26.3-$loader:generateModMetadata")?.outcome)
        }

        val updated = original.substringBefore("[metadata.mixins]").replace("marker = \"first\"", "marker = \"second\"")
        fixture.write("mod.toml", updated)
        fixture.run(*tasks, "--configuration-cache")
        assertArchives("second", mixins = false)
        for (loader in listOf("fabric", "neoforge")) {
            assertFalse(Files.exists(directory.resolve("versions/26.3-$loader/build/generated/modkit/resources/fixture.mixins.json")))
        }

        fixture.write("src/main/resources/fabric.mod.json", "{}")
        val conflict = fixture.run(":26.3-fabric:generateModMetadata", fails = true)
        assertTrue(conflict.output.contains("conflicts with hand-authored"), conflict.output)
        Files.delete(directory.resolve("src/main/resources/fabric.mod.json"))
        Files.delete(directory.resolve("LICENSE.md"))
        val missingLicence = fixture.run(":26.3-fabric:jar", fails = true)
        assertTrue(missingLicence.output.contains("modkitLicence"), missingLicence.output)
    }

    private fun assertArchives(marker: String, mixins: Boolean) {
        for (loader in listOf("fabric", "neoforge")) {
            val libs = directory.resolve("build/libs")
            for (classifier in listOf("", "-sources")) {
                ZipFile(libs.resolve("fixture-$loader-1.0.0-mc26.3$classifier.jar").toFile()).use { archive ->
                    val names = archive.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                    assertEquals(names.size, names.toSet().size)
                    assertEquals(mixins, names.contains("fixture.mixins.json"))
                    assertEquals("Fixture licence\n", archive.read("LICENSE_fixture"))
                    assertEquals("${'$'}{untouched}\n", archive.read("raw.txt"))
                    if (loader == "fabric") {
                        assertFalse(names.contains("META-INF/neoforge.mods.toml"))
                        val descriptor = JsonSlurper().parseText(archive.read("fabric.mod.json")) as Map<*, *>
                        assertEquals("Literal ${'$'}{do_not_expand} and backslash \\", descriptor["description"])
                        assertEquals("=26.3", (descriptor["depends"] as Map<*, *>)["minecraft"])
                        assertEquals(marker, (descriptor["custom"] as Map<*, *>)["marker"])
                    } else {
                        assertFalse(names.contains("fabric.mod.json"))
                        val descriptor = Toml.parse(archive.read("META-INF/neoforge.mods.toml"))
                        assertFalse(descriptor.hasErrors(), descriptor.errors().toString())
                        assertEquals("[26.3]", descriptor.getArray("dependencies.fixture")!!.getTable(1).getString("versionRange"))
                    }
                }
            }
        }
    }

    private fun ZipFile.read(name: String): String = getInputStream(getEntry(name)).bufferedReader().use { it.readText() }
}
