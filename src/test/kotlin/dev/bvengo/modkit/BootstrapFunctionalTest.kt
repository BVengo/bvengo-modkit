package dev.bvengo.modkit

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.io.CleanupMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

@Tag("functional")
internal class BootstrapFunctionalTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var directory: Path

    private val fixture: BootstrapFixture by lazy { BootstrapFixture(directory) }

    @Test
    fun `Kotlin composite builds and executes both roots for both loaders`() {
        fixture.create(kotlin = true, composite = true)
        val result = fixture.run(
            "buildAll", ":26.3-fabric:probe", ":26.3-neoforge:probe",
            ":26.3-neoforge:prepareClientRun", ":26.3-neoforge:createClientLaunchScript",
        )

        assertTrue(result.tasks.any { it.path.endsWith(":compileKotlin") }, result.output)
        assertTrue(result.output.contains("PROBE fabric fabric"), result.output)
        assertTrue(result.output.contains("PROBE neoforge neoforge"), result.output)
        assertTrue(result.output.contains("NeoForge main compilation includes client game classes"))
        assertArtifacts()

        val launchFiles = directory.resolve("versions/26.3-neoforge/build").toFile().walkTopDown()
            .filter { it.isFile && it.extension in setOf("txt", "args", "properties", "sh", "bat") }
            .map { it.readText().replace('\\', '/') }.toList().joinToString("\n")
        listOf("main", "client").forEach { sourceSet ->
            val output = "versions/26.3-neoforge/build/classes/java/$sourceSet"
            assertTrue(launchFiles.contains(output), "Prepared NeoForge run must include $sourceSet output")
        }
        assertTrue(launchFiles.contains("fixture%%"), "The native run must group the mod's outputs")
    }

    @Test
    fun `Groovy Maven markers build detached sources without composite substitution`() {
        fixture.create(kotlin = false, composite = false)
        fixture.write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)

        val result = fixture.run(
            "buildAll", ":26.3-fabric:probe", ":26.3-neoforge:probe",
            "--gradle-user-home", directory.resolve("gradle-home").toString(),
        )

        assertTrue(result.output.contains("PROBE fabric fabric"), result.output)
        assertTrue(result.output.contains("PROBE neoforge neoforge"), result.output)
        assertFalse(result.tasks.any { it.path.endsWith(":compileKotlin") })
        assertArtifacts()

        val cachedBuild = arrayOf(
            "buildAll", "--configuration-cache",
            "--gradle-user-home", directory.resolve("gradle-home").toString(),
        )
        fixture.run(*cachedBuild)
        val reused = fixture.run(*cachedBuild)
        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
    }

    @Test
    fun `Fabric rejects client game classes in common while NeoForge exposes its limitation`() {
        fixture.create(kotlin = false, composite = false)
        fixture.write("src/main/java/fixture/Unsafe.java", """
            package fixture;
            public final class Unsafe {
                public static net.minecraft.client.Minecraft game() { return null; }
            }
        """)

        val failure = fixture.run(":26.3-fabric:compileJava", fails = true)
        assertTrue(failure.output.contains("net.minecraft.client"), failure.output)

        fixture.run(":26.3-neoforge:compileJava")
    }

    @Test
    fun `neither loader permits common code to reference consumer client output`() {
        fixture.create(kotlin = false, composite = false)
        fixture.write("src/main/java/fixture/Unsafe.java", """
            package fixture;
            public final class Unsafe {
                public static String value() { return ClientOnly.value(); }
            }
        """)

        listOf("fabric", "neoforge").forEach { loader ->
            val failure = fixture.run(":26.3-$loader:compileJava", fails = true)
            assertTrue(failure.output.contains("ClientOnly"), failure.output)
        }
    }

    @Test
    fun `resources expand incrementally in both roots and archives retain templates and licence`() {
        fixture.create(kotlin = true, composite = false)
        Files.writeString(directory.resolve("build.gradle.kts"), """
            plugins { id("dev.bvengo.modkit.target") }
            modkit {
                archiveName.set("custom-fixture-" + project.name.substringAfterLast('-'))
                licenceFile.set(rootProject.layout.projectDirectory.file("LICENSE.md"))
                if (project.name.endsWith("-fabric")) {
                    licenceEntry.set("LICENSE_fixture")
                }
                metadataProperties.put("marker", providers.gradleProperty("fixture.marker"))
            }
        """.trimIndent() + "\n")
        fixture.write("LICENSE.md", "Fixture licence")
        Files.move(
            directory.resolve("src/main/resources/fabric.mod.json"),
            directory.resolve("src/client/resources/fabric.mod.json"),
        )
        fixture.write("src/client/resources/fabric.mod.json", """
            {"schemaVersion":1,"id":"fixture","version":"${'$'}{version}",
             "custom":{"marker":"${'$'}{marker}","minecraft":"${'$'}{minecraft_version}",
             "loader":"${'$'}{loader_version}","java":"${'$'}{java_version}"}}
        """)
        fixture.write("src/main/resources/META-INF/neoforge.mods.toml", """
            modLoader = "javafml"
            loaderVersion = "[4,)"
            license = "All Rights Reserved"
            [[mods]]
            modId = "fixture"
            version = "${'$'}{version}"
            displayName = "${'$'}{marker}"
        """)
        fixture.write("src/client/resources/raw.txt", "${'$'}{leave_this_alone}")

        // Let upstream plugins create their IDE files before checking cache reuse.
        fixture.run("buildAll", "-Pfixture.marker=first")
        assertResourceArchives("first")

        val arguments = arrayOf("buildAll", "--configuration-cache", "-Pfixture.marker=first")
        fixture.run(*arguments)
        val unchanged = fixture.run(*arguments)
        assertTrue(unchanged.output.contains("Configuration cache entry reused"), unchanged.output)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":26.3-fabric:processClientResources")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":26.3-neoforge:processResources")?.outcome)

        fixture.run("buildAll", "--configuration-cache", "-Pfixture.marker=second")
        assertResourceArchives("second")

        Files.delete(directory.resolve("LICENSE.md"))
        val failure = fixture.run(":26.3-fabric:jar", "-Pfixture.marker=second", fails = true)
        assertTrue(failure.output.contains("modkitLicence"), failure.output)
        assertTrue(failure.output.contains("doesn't exist"), failure.output)
    }

    private fun assertResourceArchives(marker: String) {
        listOf("fabric", "neoforge").forEach { loader ->
            val libs = directory.resolve("versions/26.3-$loader/build/libs")
            val descriptor = if (loader == "fabric") "fabric.mod.json" else "META-INF/neoforge.mods.toml"
            val excluded = if (loader == "fabric") "META-INF/neoforge.mods.toml" else "fabric.mod.json"
            val licenceEntry = if (loader == "fabric") "LICENSE_fixture" else "LICENSE.md_fixture-neoforge"

            ZipFile(libs.resolve("custom-fixture-$loader-1.0.0-mc26.3.jar").toFile()).use { archive ->
                val metadata = archive.getInputStream(archive.getEntry(descriptor)).bufferedReader().use { it.readText() }
                assertTrue(metadata.contains("1.0.0-mc26.3"), metadata)
                assertTrue(metadata.contains(marker), metadata)
                assertFalse(metadata.contains("${'$'}{"), metadata)
                assertEquals(null, archive.getEntry(excluded))
                assertEquals("${'$'}{leave_this_alone}\n", archive.readEntry("raw.txt"))
                assertEquals("Fixture licence\n", archive.readEntry(licenceEntry))
                if (loader == "fabric") {
                    assertTrue(metadata.contains("26.3"), metadata)
                    assertTrue(metadata.contains("0.19.5"), metadata)
                    assertTrue(metadata.contains("25"), metadata)
                }
            }

            val sources = libs.resolve("custom-fixture-$loader-1.0.0-mc26.3-sources.jar")
            assertEntries(sources, listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", licenceEntry))
            ZipFile(sources.toFile()).use { archive ->
                assertTrue(archive.readEntry(descriptor).contains("${'$'}{version}"))
            }
        }
    }

    private fun ZipFile.readEntry(name: String): String {
        return getInputStream(getEntry(name)).bufferedReader().use { it.readText() }
    }

    private fun assertArtifacts() {
        listOf("fabric", "neoforge").forEach { loader ->
            val libs = directory.resolve("versions/26.3-$loader/build/libs")
            val binary = libs.resolve("fixture-$loader-1.0.0-mc26.3.jar")
            val sources = libs.resolve("fixture-$loader-1.0.0-mc26.3-sources.jar")
            assertEntries(binary, listOf("fixture/Common.class", "fixture/ClientOnly.class", "common.txt", "client.txt"))
            assertEntries(sources, listOf("fixture/Common.java", "fixture/ClientOnly.java", "common.txt", "client.txt"))
        }
    }

    private fun assertEntries(path: Path, expected: List<String>) {
        ZipFile(path.toFile()).use { archive ->
            val names = archive.entries().asSequence().map { it.name }.toList()
            expected.forEach { name -> assertEquals(1, names.count { it == name }, "$path: $name") }
        }
    }
}
