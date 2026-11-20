package dev.bvengo.modkit

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path

/** Creates real consumer builds without injecting the plugin classpath. */
internal class BootstrapFixture(private val directory: Path) {
    fun create(kotlin: Boolean, composite: Boolean) {
        val implementation = Path.of(System.getProperty("modkit.projectDirectory")).toUri()
        val repository = Path.of(System.getProperty("modkit.testRepository")).toUri()
        val bootstrap = if (composite) "includeBuild(java.io.File(java.net.URI(\"$implementation\")).absolutePath)" else ""
        val mavenRepository = if (composite) "" else "maven { url = uri(\"$repository\") }"
        val settings = """
            pluginManagement {
                $bootstrap
                repositories {
                    $mavenRepository
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

    fun run(vararg tasks: String, fails: Boolean = false): BuildResult {
        val runner = GradleRunner.create()
            .withProjectDir(directory.toFile())
            .withArguments(*tasks, "--stacktrace", "--console=plain", "--max-workers=2")
            .forwardOutput()

        return if (fails) runner.buildAndFail() else runner.build()
    }

    fun write(relative: String, contents: String) {
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, contents.trimIndent() + "\n")
    }
}
