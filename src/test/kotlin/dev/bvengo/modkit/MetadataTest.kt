package dev.bvengo.modkit

import dev.bvengo.modkit.model.Target
import dev.bvengo.modkit.registry.DependencyRegistry
import dev.bvengo.modkit.registry.ManifestReader
import dev.bvengo.modkit.registry.TargetRegistry
import dev.bvengo.modkit.target.MetadataRenderer
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tomlj.Toml

internal class MetadataTest {
    private val base = """
        schema = 1
        [mod]
        id = "fixture"
        group = "dev.bvengo.fixture"
        version = "1.0.0"
        [targets]
        minecraft = ["26.1", "26.3"]
        loaders = ["fabric", "neoforge"]
        vcs = "26.3-fabric"
    """.trimIndent()

    private val metadata = """
        [metadata]
        name = 'Fixture "name"'
        description = 'Line with ${'$'}{literal} and backslash \'
        licence = "All Rights Reserved"
        environment = "client"
        authors = ["Example"]
        [metadata.entrypoints]
        client = ["fixture.Client"]
        [metadata.fabric.depends]
        fabricloader = ">=0.18.4"
        fabric-api = "*"
        [metadata.fabric.recommends]
        modmenu = "*"
        [metadata.neoforge.depends]
        bvengomodcore = "[0.1.0,0.2.0)"
        [metadata.fabric.custom.menu]
        enabled = true
        values = [1, "text"]
        [metadata.mixins]
        package = "fixture.mixin"
        client = ["ClientMixin"]
    """.trimIndent()

    @Test
    fun `generated descriptors round trip literals and preserve loader relationships`() {
        val manifest = ManifestReader.read("$base\n$metadata", "fixture/mod.toml")
        for (loader in listOf("fabric", "neoforge")) {
            val target = Target("26.3", loader)
            val pins = TargetRegistry.load(target)
            val files = MetadataRenderer.render(manifest, target, pins, mapOf(
                "version" to "1.0.0-mc26.3", "loader_version" to pins.loaderVersion,
                "java_version" to "25", "minecraft_range" to pins.minecraftRange,
            ))
            val mixins = JsonSlurper().parseText(files.getValue("fixture.mixins.json")) as Map<*, *>
            assertEquals(listOf("ClientMixin"), mixins["client"])
            assertEquals("JAVA_25", mixins["compatibilityLevel"])
            if (loader == "fabric") {
                val descriptor = JsonSlurper().parseText(files.getValue("fabric.mod.json")) as Map<*, *>
                assertEquals(manifest.metadata!!.name, descriptor["name"])
                assertEquals(manifest.metadata.description, descriptor["description"])
                assertEquals(
                    mapOf("fabricloader" to ">=0.18.4", "minecraft" to ">=26.3", "java" to ">=25", "fabric-api" to "*"),
                    descriptor["depends"],
                )
                assertEquals(mapOf("modmenu" to "*"), descriptor["recommends"])
                assertEquals(mapOf("menu" to mapOf("enabled" to true, "values" to listOf(1, "text"))), descriptor["custom"])
                assertFalse(files.containsKey("META-INF/neoforge.mods.toml"))
            } else {
                val descriptor = Toml.parse(files.getValue("META-INF/neoforge.mods.toml"))
                assertFalse(descriptor.hasErrors(), descriptor.errors().toString())
                val mod = descriptor.getArray("mods")!!.getTable(0)
                assertEquals(manifest.metadata!!.description, mod.getString("description"))
                assertEquals(manifest.metadata.name, mod.getString("displayName"))
                val dependencies = descriptor.getArray("dependencies.fixture")!!
                assertEquals("[26.3,)", dependencies.getTable(1).getString("versionRange"))
                assertEquals("CLIENT", dependencies.getTable(0).getString("side"))
                assertEquals("[4,)", descriptor.getString("loaderVersion"))
                val sharedLibrary = dependencies.getTable(2)
                assertEquals("bvengomodcore", sharedLibrary.getString("modId"))
                assertEquals("[0.1.0,0.2.0)", sharedLibrary.getString("versionRange"))
                assertEquals("required", sharedLibrary.getString("type"))
                assertEquals("CLIENT", sharedLibrary.getString("side"))
            }
        }
    }

    @Test
    fun `registry ranges stay explicit across skipped targets and consumer overrides`() {
        val manifest = ManifestReader.read(base + """

            [compatibility."26.1"]
            fabric = "=26.1"
            neoforge = "[26.1]"
        """.trimIndent(), "mod.toml")
        assertEquals("=26.1", manifest.compatibility["26.1"]?.get("fabric"))
        assertEquals(">=26.1 <26.2", TargetRegistry.load(Target("26.1", "fabric")).minecraftRange)
        assertEquals("[26.1,26.2)", TargetRegistry.load(Target("26.1", "neoforge")).minecraftRange)
    }

    @Test
    fun `loader scoped annotations do not change Fabric or silently enable other dependencies`() {
        val manifest = ManifestReader.read(base + """

            [dependencies]
            annotations = { loaders = ["neoforge"] }
        """.trimIndent(), "mod.toml")
        assertTrue(DependencyRegistry.resolve(manifest, Target("26.3", "fabric")).isEmpty())
        assertEquals(listOf("annotations"), DependencyRegistry.resolve(manifest, Target("26.3", "neoforge")).map { it.key })
    }

    @Test
    fun `invalid metadata packaging compatibility and dependency scopes fail with source locations`() {
        val invalid = listOf(
            "$base\n$metadata".replace("environment = \"client\"", "environment = \"server\""),
            "$base\n$metadata".replace("name = 'Fixture \"name\"'", "name = 12"),
            "$base\n$metadata".replace("client = [\"ClientMixin\"]", "client = []"),
            "$base\n$metadata".replace("client = [\"ClientMixin\"]", "client = [\"ClientMixin\", \"ClientMixin\"]"),
            "$base\n$metadata\n[metadata.unknown]\nvalue = true",
            "$base\n$metadata\n[metadata.neoforge.unknown]\nvalue = true",
            "$base\n$metadata\nmetadata.neoforge.depends = true",
            "$base\n$metadata".replace("bvengomodcore = \"[0.1.0,0.2.0)\"", "minecraft = \"[26.3,)\""),
            "$base\n$metadata".replace("bvengomodcore = \"[0.1.0,0.2.0)\"", "bad-id = \"[0.1.0,)\""),
            "$base\n$metadata\n[metadata.fabric.custom.bad]\nvalue = 2026-11-23",
            "$base\n[packaging]\nlicence_file = \"../LICENSE\"",
            "$base\n[packaging]\nlicence_file = \"/tmp/LICENSE\"",
            "$base\n[packaging]\nlicence_file = 'C:/LICENSE'",
            "$base\n[packaging]\nlicence_file = '//server/share/LICENSE'",
            "$base\n[packaging]\nlicence_entry = \"LICENSE\"",
            "$base\n[publishing]\nmaven = \"yes\"",
            "$base\n[compatibility.\"26.2\"]\nfabric = \"*\"",
            "$base\n[compatibility.\"26.1\"]\nforge = \"*\"",
            "$base\n[dependencies]\nannotations = { loaders = [] }",
            "$base\n[dependencies]\nannotations = { loaders = [\"forge\"] }",
            "$base\n[dependencies]\nannotations = { loaders = [\"fabric\", \"fabric\"] }",
            "$base\n[dependencies]\nmodmenu = { loaders = [\"neoforge\"] }",
            "$base\n[dependencies]\nannotations = { loader = \"neoforge\" }",
        )
        val disabledOverride = "$base\n[dependencies]\nannotations = { loaders = [\"neoforge\"] }" +
            "\n[overrides.targets.\"26.3.fabric\".dependencies]\nannotations = \"26.1.0\""
        for (text in invalid + disabledOverride) {
            val failure = assertThrows(GradleException::class.java) { ManifestReader.read(text, "fixture/mod.toml") }
            assertTrue(failure.message!!.startsWith("fixture/mod.toml:"), failure.message)
        }
    }
}
