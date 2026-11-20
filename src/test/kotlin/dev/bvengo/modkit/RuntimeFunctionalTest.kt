package dev.bvengo.modkit

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("runtime")
internal class RuntimeFunctionalTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var directory: Path

    private val fixture: BootstrapFixture by lazy { BootstrapFixture(directory) }

    @Test
    fun `native clients initialise both roots and reach the title screen`() {
        createRuntimeFixture()

        listOf("fabric", "neoforge").forEach { loader ->
            val result = fixture.run(":26.3-$loader:runClient")
            assertTrue(result.output.contains("MODKIT_CLIENT_ENTRYPOINT"), result.output)
            assertTrue(result.output.contains("MODKIT_TITLE_READY $loader"), result.output)
        }
    }

    @Test
    fun `NeoForge server excludes client entrypoints and rejects an exercised client reference in common`() {
        check(java.lang.Boolean.getBoolean("modkit.test.acceptEula")) {
            "Server runtime tests require -Pmodkit.test.acceptEula=true for the temporary server."
        }
        createRuntimeFixture()
        fixture.write("run/neoforge-server/eula.txt", "eula=true")
        fixture.write("run/neoforge-server/server.properties", """
            online-mode=false
            server-ip=127.0.0.1
            server-port=0
            level-type=minecraft:flat
            generate-structures=false
            view-distance=2
            simulation-distance=2
        """)

        val server = fixture.run(":26.3-neoforge:runSmokeServer")
        assertTrue(server.output.contains("MODKIT_SERVER_READY neoforge"), server.output)
        assertFalse(server.output.contains("MODKIT_CLIENT_ENTRYPOINT"), server.output)

        fixture.write("src/main/java/fixture/Unsafe.java", """
            package fixture;
            //? if neoforge {
            /*@net.neoforged.fml.common.Mod("fixture")
            public final class Unsafe {
                public Unsafe() {
                    net.minecraft.client.Minecraft.getInstance();
                }
            }
            *///?}
        """)
        val unsafe = fixture.run(":26.3-neoforge:runSmokeServer", fails = true)
        assertTrue(unsafe.output.contains("net/minecraft/client/Minecraft"), unsafe.output)
        assertTrue(unsafe.output.contains("DEDICATED_SERVER"), unsafe.output)
        assertFalse(unsafe.output.contains("MODKIT_SERVER_READY"), unsafe.output)
    }

    private fun createRuntimeFixture() {
        fixture.create(kotlin = false, composite = false)
        val resources = Path.of(requireNotNull(javaClass.getResource("/runtime")).toURI())
        Files.walk(resources).use { paths ->
            paths.filter(Files::isRegularFile).forEach { path ->
                fixture.write(resources.relativize(path).toString(), Files.readString(path))
            }
        }
        Files.writeString(directory.resolve("build.gradle"), """
            plugins { id("dev.bvengo.modkit.target") }
            if (project.name.endsWith("-fabric")) {
                loom.runs.named("client") {
                    programArguments.addAll("--width", "640", "--height", "480")
                }
            } else {
                neoForge.runs.register("smokeServer") {
                    server()
                    gameDirectory.set(rootProject.layout.projectDirectory.dir("run/neoforge-server"))
                    programArgument("nogui")
                }
            }
            tasks.withType(JavaExec).configureEach {
                timeout.set(java.time.Duration.ofMinutes(3))
                maxHeapSize = "1G"
            }
        """.trimIndent() + "\n")
        fixture.write("run/fabric/options.txt", "onboardAccessibility:false")
        fixture.write("run/neoforge/options.txt", "onboardAccessibility:false")
    }
}
