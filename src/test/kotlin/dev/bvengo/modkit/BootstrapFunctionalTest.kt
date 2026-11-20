package dev.bvengo.modkit

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
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

    @Test
    fun `Kotlin composite builds and executes both roots for both loaders`() {
        createFixture(kotlin = true, composite = true)
        val result = run(
            "buildAll", ":26.3-fabric:probe", ":26.3-neoforge:probe",
            ":26.3-neoforge:prepareClientRun", ":26.3-neoforge:createClientLaunchScript",
        )

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
        createFixture(kotlin = false, composite = false)
        write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)

        val result = run(
            "buildAll", ":26.3-fabric:probe", ":26.3-neoforge:probe",
            "--gradle-user-home", directory.resolve("gradle-home").toString(),
        )

        assertTrue(result.output.contains("PROBE fabric fabric"), result.output)
        assertTrue(result.output.contains("PROBE neoforge neoforge"), result.output)
        assertFalse(result.output.contains(":modkit:compileKotlin"))
        assertArtifacts()

        val cachedBuild = arrayOf(
            "buildAll", "--configuration-cache",
            "--gradle-user-home", directory.resolve("gradle-home").toString(),
        )
        run(*cachedBuild)
        val reused = run(*cachedBuild)
        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
    }

    @Test
    fun `Fabric rejects client game classes in common while NeoForge exposes its limitation`() {
        createFixture(kotlin = false, composite = false)
        write("src/main/java/fixture/Unsafe.java", """
            package fixture;
            public final class Unsafe {
                public static net.minecraft.client.Minecraft game() { return null; }
            }
        """)

        val failure = run(":26.3-fabric:compileJava", fails = true)
        assertTrue(failure.output.contains("net.minecraft.client"), failure.output)

        run(":26.3-neoforge:compileJava")
    }

    @Test
    fun `neither loader permits common code to reference consumer client output`() {
        createFixture(kotlin = false, composite = false)
        write("src/main/java/fixture/Unsafe.java", """
            package fixture;
            public final class Unsafe {
                public static String value() { return ClientOnly.value(); }
            }
        """)

        listOf("fabric", "neoforge").forEach { loader ->
            val failure = run(":26.3-$loader:compileJava", fails = true)
            assertTrue(failure.output.contains("ClientOnly"), failure.output)
        }
    }

    private fun createFixture(kotlin: Boolean, composite: Boolean) {
        val implementation = Path.of(System.getProperty("modkit.projectDirectory")).toUri()
        val repository = Path.of(System.getProperty("modkit.testRepository")).toUri()
        val bootstrap = if (composite) "includeBuild(file(java.net.URI(\"$implementation\")))" else ""
        val settings = """
            pluginManagement {
                $bootstrap
                repositories {
                    maven { url = uri("$repository") }
                    maven { url = uri("https://maven.kikugie.dev/releases") }
                    maven { url = uri("https://maven.fabricmc.net/") }
                    maven { url = uri("https://maven.neoforged.net/releases/") }
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            plugins { id("dev.bvengo.modkit.settings") version "0.1.0" }
            rootProject.name = "fixture"
        """
        write(if (kotlin) "settings.gradle.kts" else "settings.gradle", settings)
        write("gradle.properties", """
            org.gradle.jvmargs=-Xmx1G
            org.gradle.workers.max=2
        """)
        write("mod.toml", """
            schema = 1
            [mod]
            id = "fixture"
            group = "dev.bvengo.fixture"
            version = "1.0.0"
            [targets]
            minecraft = ["26.3"]
            loaders = ["fabric", "neoforge"]
            vcs = "26.3-fabric"
        """)
        write(if (kotlin) "stonecutter.gradle.kts" else "stonecutter.gradle", """
            plugins { id("dev.bvengo.modkit.controller") }
            stonecutter.active("26.3-fabric")
        """)

        val probe = if (kotlin) """
            val sources = extensions.getByType<SourceSetContainer>()
            tasks.register<JavaExec>("probe") {
                classpath = sources["client"].runtimeClasspath
                mainClass.set("fixture.ClientOnly")
            }
        """ else """
            tasks.register("probe", JavaExec) {
                classpath = sourceSets.client.runtimeClasspath
                mainClass = "fixture.ClientOnly"
            }
        """
        write(if (kotlin) "build.gradle.kts" else "build.gradle", """
            plugins { id("dev.bvengo.modkit.target") }
            $probe
        """)
        write("src/main/java/fixture/Common.java", """
            package fixture;
            public final class Common {
                public static String value() {
                    //? if fabric {
                    return "fabric";
                    //?} else {
                    /*return "neoforge";
                    *///?}
                }
                public static net.minecraft.network.chat.Component component() { return null; }
            }
        """)
        write("src/client/java/fixture/ClientOnly.java", """
            package fixture;
            public final class ClientOnly {
                public static String value() {
                    //? if fabric {
                    return "fabric";
                    //?} else {
                    /*return "neoforge";
                    *///?}
                }
                public static net.minecraft.client.Minecraft game() { return null; }
                public static void main(String[] args) {
                    System.out.println("PROBE " + Common.value() + " " + value());
                }
            }
        """)
        write("src/main/resources/common.txt", "common resource")
        write("src/client/resources/client.txt", "client resource")
        write("src/main/resources/fabric.mod.json", """
            {"schemaVersion":1,"id":"fixture","version":"1.0.0","environment":"client"}
        """)
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

    private fun run(vararg tasks: String, fails: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withArguments(*tasks, "--stacktrace", "--console=plain", "--max-workers=2")
            .forwardOutput()

        return if (fails) runner.buildAndFail() else runner.build()
    }

    private fun write(relative: String, contents: String) {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, contents.trimIndent() + "\n")
    }
}
