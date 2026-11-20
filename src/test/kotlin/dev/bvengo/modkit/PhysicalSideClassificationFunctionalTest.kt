package dev.bvengo.modkit

import de.thetaphi.forbiddenapis.Checker
import de.thetaphi.forbiddenapis.ForbiddenApiException
import de.thetaphi.forbiddenapis.Logger
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.regex.Pattern
import java.util.spi.ToolProvider

/** Records classification limits without changing the production namespace policy. */
@Tag("functional")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PhysicalSideClassificationFunctionalTest {
    private lateinit var directory: Path
    private lateinit var fixture: BootstrapFixture
    private lateinit var compileClasspath: List<Path>

    private val outsideNamespaceDeclarations = linkedMapOf(
        "RealmsBody" to "public Object unused() { return net.minecraft.realms.RealmsScreen.class; }",
        "RealmsField" to "public net.minecraft.realms.RealmsScreen value;",
        "RealmsGeneric" to "public java.util.List<net.minecraft.realms.RealmsScreen> value;",
        "RealmsCast" to "public Object unused(Object value) { return (net.minecraft.realms.RealmsScreen) value; }",
    )

    @BeforeAll
    fun compileClassificationControls(@TempDir(cleanup = CleanupMode.ON_SUCCESS) fixtureDirectory: Path) {
        directory = fixtureDirectory
        fixture = BootstrapFixture(directory)
        fixture.create(kotlin = false, composite = false)

        val manifest = directory.resolve("mod.toml")
        Files.writeString(
            manifest,
            Files.readString(manifest).replace("[\"fabric\", \"neoforge\"]", "[\"neoforge\"]")
                .replace("26.3-fabric", "26.3-neoforge"),
        )
        fixture.write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)

        val memberDeclarations = mapOf(
            "ClientMethodUse" to "public Object unused() { return ClassifiedApi.clientMethod(); }",
            "ClientFieldUse" to "public Object unused() { return ClassifiedApi.clientField; }",
            "SafeMethodUse" to "public Object unused() { return ClassifiedApi.commonMethod(); }",
        )
        (outsideNamespaceDeclarations + memberDeclarations).forEach { (name, declaration) ->
            fixture.write("src/main/java/fixture/$name.java", """
                package fixture;
                public final class $name {
                    //? if neoforge {
                    /*$declaration
                    *///?}
                }
            """)
        }

        // Synthetic member policy proves checker capability, not classification of the game API.
        fixture.write("src/main/java/fixture/ClassifiedApi.java", """
            package fixture;
            public final class ClassifiedApi {
                public static Object clientField;
                public static Object clientMethod() { return null; }
                public static Object commonMethod() { return null; }
            }
        """)
        val buildFile = directory.resolve("build.gradle")
        Files.writeString(buildFile, Files.readString(buildFile) + """

            def checkerClasspath = sourceSets.main.compileClasspath
            def checkerClasspathFile = layout.buildDirectory.file("checker-classpath.txt")
            tasks.register("writeCheckerClasspath") {
                inputs.files(checkerClasspath)
                outputs.file(checkerClasspathFile)
                doLast {
                    checkerClasspathFile.get().asFile.text = checkerClasspath.asPath
                }
            }
        """.trimIndent() + "\n")

        fixture.run(":26.3-neoforge:compileJava", ":26.3-neoforge:writeCheckerClasspath")
        val classpathFile = directory.resolve("versions/26.3-neoforge/build/checker-classpath.txt")
        compileClasspath = Files.readString(classpathFile).split(File.pathSeparator).map(Path::of)

        outsideNamespaceDeclarations.keys.forEach { name ->
            val bytecode = Files.readAllBytes(classFile(name))
            assertEquals(69, ((bytecode[6].toInt() and 255) shl 8) or (bytecode[7].toInt() and 255))
            assertTrue(String(bytecode, Charsets.ISO_8859_1).contains("net/minecraft/realms/RealmsScreen"), name)
        }
    }

    @Test
    fun `current namespace task accepts a manifest-tagged client type outside its policy`() {
        val matchingJars = compileClasspath.filter { path ->
            if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) {
                false
            } else {
                JarFile(path.toFile()).use { jar ->
                    jar.getJarEntry("net/minecraft/realms/RealmsScreen.class") != null
                }
            }
        }
        assertEquals(1, matchingJars.size, matchingJars.toString())
        JarFile(matchingJars.single().toFile()).use { jar ->
            assertEquals("client server", jar.manifest.mainAttributes.getValue("Minecraft-Dists"))
            val attributes = jar.manifest.getAttributes("net/minecraft/realms/RealmsScreen.class")
            assertEquals("client", attributes?.getValue("Minecraft-Dist"))
        }

        val result = fixture.run(":26.3-neoforge:checkCommonSide")
        assertEquals(TaskOutcome.SUCCESS, result.task(":26.3-neoforge:checkCommonSide")?.outcome)
    }

    @Test
    fun `jdeps detects the classified type including generic and cast references`() {
        val clientType = "net.minecraft.realms.RealmsScreen"
        outsideNamespaceDeclarations.keys.forEach { name ->
            val output = dependencies(name, Pattern.quote(clientType))
            assertTrue(output.contains(clientType), output)
        }
        assertEquals("", dependencies("Common", Pattern.quote(clientType)))
    }

    @Test
    fun `jdeps class dependencies cannot distinguish members of a shared owner`() {
        val owner = Pattern.quote("fixture.ClassifiedApi")
        listOf("ClientMethodUse", "ClientFieldUse", "SafeMethodUse").forEach { name ->
            val output = dependencies(name, owner)
            assertTrue(output.contains("fixture.ClassifiedApi"), output)
            assertEquals(false, output.contains("clientMethod"), output)
            assertEquals(false, output.contains("clientField"), output)
            assertEquals(false, output.contains("commonMethod"), output)
        }
    }

    @Test
    fun `Forbidden APIs distinguishes explicitly classified members without banning their owner`() {
        val classes = directory.resolve("versions/26.3-neoforge/build/classes/java/main")
        val urls = (compileClasspath + listOf(classes)).map { it.toUri().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            assertTrue(forbiddenMemberUse("ClientMethodUse", loader))
            assertTrue(forbiddenMemberUse("ClientFieldUse", loader))
            assertEquals(false, forbiddenMemberUse("SafeMethodUse", loader))
        }
    }

    private fun dependencies(name: String, expression: String): String {
        val output = StringWriter()
        val errors = StringWriter()
        val checker = ToolProvider.findFirst("jdeps").orElseThrow()
        val result = checker.run(
            PrintWriter(output), PrintWriter(errors), "-verbose:class", "-filter:none",
            "-regex", expression, classFile(name).toString(),
        )

        assertEquals(0, result, errors.toString())
        assertEquals("", errors.toString())

        return output.toString()
    }

    private fun forbiddenMemberUse(name: String, loader: ClassLoader): Boolean {
        val errors = mutableListOf<String>()
        val logger = object : Logger {
            override fun error(message: String) {
                errors.add(message)
            }

            override fun warn(message: String) {
                throw AssertionError("Incomplete checker resolution: $message")
            }

            override fun info(message: String) {}
            override fun debug(message: String) {}
        }
        val checker = Checker(
            logger, loader, Checker.Option.FAIL_ON_MISSING_CLASSES,
            Checker.Option.FAIL_ON_VIOLATION, Checker.Option.FAIL_ON_UNRESOLVABLE_SIGNATURES,
        )
        checker.parseSignaturesString("fixture.ClassifiedApi#clientMethod()\nfixture.ClassifiedApi#clientField")
        checker.addClassToCheck(classFile(name).toFile())

        return try {
            checker.run()
            false
        } catch (failure: ForbiddenApiException) {
            assertTrue(errors.any { it.contains("fixture.ClassifiedApi") }, errors.joinToString("\n"))
            true
        }
    }

    private fun classFile(name: String): Path {
        return directory.resolve("versions/26.3-neoforge/build/classes/java/main/fixture/$name.class")
    }
}
