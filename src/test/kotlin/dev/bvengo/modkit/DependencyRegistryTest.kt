package dev.bvengo.modkit

import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.DependencyRegistry
import dev.bvengo.modkit.registry.ManifestReader
import dev.bvengo.modkit.registry.TargetRegistry
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class DependencyRegistryTest {
    private val baseManifest = """
        schema = 1
        [mod]
        id = "fixture"
        group = "dev.bvengo.fixture"
        version = "1.0.0"
        [targets]
        minecraft = ["26.3"]
        loaders = ["fabric", "neoforge"]
        vcs = "26.3-fabric"
    """.trimIndent()

    @Test
    fun `dependencies are absent unless explicitly enabled`() {
        listOf("", "\n[dependencies]\nfabric_api = false\nmodmenu = false").forEach { declarations ->
            val manifest = ManifestReader.read(baseManifest + declarations, "mod.toml")
            manifest.targets.forEach { target ->
                assertEquals(emptyList<Any>(), DependencyRegistry.resolve(manifest, target))
            }
        }
    }

    @Test
    fun `registry defines exact pins and source placement for enabled dependencies`() {
        val manifest = ManifestReader.read(
            baseManifest.replace("[\"fabric\", \"neoforge\"]", "[\"fabric\"]") + """

                [dependencies]
                fabric_api = true
                modmenu = true
                annotations = true
                yacl = true
            """.trimIndent(),
            "mod.toml",
        )
        val dependencies = DependencyRegistry.resolve(manifest, Target("26.3", "fabric"))

        assertEquals(
            listOf("0.160.5+26.3", "21.0.0-beta.1", "26.1.0", "3.9.7+26.3-fabric"),
            dependencies.map { it.version },
        )
        assertEquals(
            listOf("implementation", "clientImplementation", "compileOnly", "clientImplementation"),
            dependencies.map { it.definition.configuration },
        )
    }

    @Test
    fun `overrides select target then Minecraft then global then registry`() {
        val layers = listOf(
            "" to "0.160.5+26.3",
            """
                [overrides.dependencies]
                fabric_api = "0.153.2+26.3"
            """ to "0.153.2+26.3",
            """
                [overrides.minecraft."26.3".dependencies]
                fabric_api = "0.160.5+26.3"
            """ to "0.160.5+26.3",
            """
                [overrides.targets."26.3.fabric".dependencies]
                fabric_api = "0.153.2+26.3"
            """ to "0.153.2+26.3",
        )
        var text = baseManifest + "\n[dependencies]\nfabric_api = true\n"
        layers.forEach { (declaration, expected) ->
            text += declaration.trimIndent() + "\n"
            val manifest = ManifestReader.read(text, "mod.toml")
            val dependency = DependencyRegistry.resolve(manifest, Target("26.3", "fabric")).single()
            assertEquals(expected, dependency.version)
            assertTrue(dependency.origin.contains("fabric_api"))
        }
    }

    @Test
    fun `Minecraft and loader scopes do not leak to another target`() {
        val text = baseManifest.replace("[\"26.3\"]", "[\"26.2\", \"26.3\"]") + """

            [dependencies]
            annotations = true
            [overrides.minecraft."26.3".dependencies]
            annotations = "26.0.0"
            [overrides.targets."26.3.fabric".dependencies]
            annotations = "26.0.1"
        """.trimIndent()
        val manifest = ManifestReader.read(text, "mod.toml")
        val actual = manifest.targets.associate { target ->
            target.projectName to DependencyRegistry.resolve(manifest, target).single().version
        }

        assertEquals(
            mapOf("26.2-fabric" to "26.1.0", "26.2-neoforge" to "26.1.0",
                "26.3-fabric" to "26.0.1", "26.3-neoforge" to "26.0.0"),
            actual,
        )
    }

    @Test
    fun `Fabric API and ModMenu skip NeoForge while annotations remain available`() {
        val manifest = ManifestReader.read(baseManifest + """

            [dependencies]
            fabric_api = true
            modmenu = true
            annotations = true
        """.trimIndent(), "mod.toml")

        val dependencies = DependencyRegistry.resolve(manifest, Target("26.3", "neoforge"))
        assertEquals(listOf("annotations"), dependencies.map { it.key })
    }

    @Test
    fun `unsupported YACL platforms and absent target pins fail before project creation`() {
        val cases = listOf(
            baseManifest + "\n[dependencies]\nyacl = true",
            baseManifest.replace("[\"fabric\", \"neoforge\"]", "[\"fabric\"]")
                .replace("26.3", "26.2") + "\n[dependencies]\nyacl = true",
        )
        cases.forEach { text ->
            val failure = assertThrows(GradleException::class.java) { ManifestReader.read(text, "mod.toml") }
            assertTrue(failure.message!!.contains("dependencies.yacl"), failure.message)
        }
    }

    @Test
    fun `invalid override declarations fail with their manifest location`() {
        val enabled = baseManifest + "\n[dependencies]\nannotations = true\n"
        val cases = listOf(
            baseManifest + "\n[dependencies]\nannotations = \"true\"",
            baseManifest + "\n[overrides.dependencies]\nannotations = \"26.1.0\"",
            enabled + "[overrides.dependencies]\nunknown = \"1.0\"",
            enabled + "[overrides.targets.\"26.3.forge\".dependencies]\nannotations = \"26.1.0\"",
            enabled + "[overrides.minecraft.\"26.1\".dependencies]\nannotations = \"26.1.0\"",
            enabled + "[overrides.minecraft.\"26.3\"]\nunknown = true",
            enabled + "[overrides.dependencies]\nannotations = 26",
        ) + listOf("", "26.+", "latest.release", "[26,27)", "26.1-SNAPSHOT").map { version ->
            enabled + "[overrides.dependencies]\nannotations = \"$version\""
        }

        cases.forEach { text ->
            val failure = assertThrows(GradleException::class.java) { ManifestReader.read(text, "fixture/mod.toml") }
            assertTrue(failure.message!!.startsWith("fixture/mod.toml:"), failure.message)
        }
    }

    @Test
    fun `all recorded target pins retain the established loader versions`() {
        val expected = mapOf(
            "26.1" to listOf("0.18.4", "26.1.0.19-beta"),
            "26.2" to listOf("0.19.3", "26.2.0.7-beta"),
            "26.3" to listOf("0.19.5", "26.3.0.1-beta"),
        )
        expected.forEach { (minecraft, versions) ->
            val pins = listOf("fabric", "neoforge").map { TargetRegistry.load(Target(minecraft, it)) }
            assertEquals(versions, pins.map { it.loaderVersion })
            assertTrue(pins.all { it.java == 25 })
        }
    }
}
