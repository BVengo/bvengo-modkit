package dev.bvengo.modkit

import dev.bvengo.modkit.registry.ManifestReader
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ManifestReaderTest {
    private val manifest = """
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
    fun `target selection retains explicit loader identities`() {
        val result = ManifestReader.read(manifest, "fixture/mod.toml")

        assertEquals(listOf("26.3-fabric", "26.3-neoforge"), result.targets.map { it.projectName })
        assertEquals("dev.bvengo.fixture", result.group)
    }

    @Test
    fun `invalid declarations fail with the manifest location`() {
        val cases = listOf(
            manifest.replace("schema = 1", "schema = 2"),
            manifest.replace("schema = 1", "schema = true"),
            manifest.replace("version = \"1.0.0\"", "version = 7"),
            manifest.replace("[\"26.3\"]", "[26.3]"),
            manifest.replace("[\"26.3\"]", "[\"26.3\", \"26.3\"]"),
            manifest.replace("[\"26.3\"]", "[\"25.9\"]"),
            manifest.replace("[\"fabric\", \"neoforge\"]", "[\"forge\"]"),
            manifest.replace("vcs = \"26.3-fabric\"", "vcs = \"missing\""),
            manifest + "\n[dependencies]\nunknown = true",
            manifest.replace("schema = 1", "schema = ["),
        )

        cases.forEach { invalid ->
            val failure = assertThrows(GradleException::class.java) {
                ManifestReader.read(invalid, "fixture/mod.toml")
            }
            assertTrue(failure.message!!.startsWith("fixture/mod.toml:"), failure.message)
        }
    }
}
