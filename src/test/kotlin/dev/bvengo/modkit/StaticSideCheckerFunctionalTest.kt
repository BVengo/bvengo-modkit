package dev.bvengo.modkit

import com.tngtech.archunit.ArchConfiguration
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import de.thetaphi.forbiddenapis.Checker
import de.thetaphi.forbiddenapis.ForbiddenApiException
import de.thetaphi.forbiddenapis.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.spi.ToolProvider

/** Evaluates checker coverage without presenting either candidate as production enforcement. */
@Tag("functional")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class StaticSideCheckerFunctionalTest {
    private lateinit var directory: Path

    private val declarations = linkedMapOf(
        "DormantBody" to "public static Object unused() { return net.minecraft.client.Minecraft.getInstance(); }",
        "FieldType" to "public net.minecraft.client.Minecraft value;",
        "ReturnType" to "public net.minecraft.client.Minecraft unused() { return null; }",
        "ParameterType" to "public void unused(net.minecraft.client.Minecraft value) {}",
        "GenericField" to "public java.util.List<net.minecraft.client.Minecraft> value;",
        "GenericReturn" to "public java.util.List<net.minecraft.client.Minecraft> unused() { return null; }",
        "GenericParameter" to "public void unused(java.util.List<net.minecraft.client.Minecraft> value) {}",
        "GenericBound" to "public <T extends java.util.List<net.minecraft.client.Minecraft>> void unused(T value) {}",
        "CastOnly" to "public Object unused(Object value) { return (net.minecraft.client.Minecraft) value; }",
    )

    @BeforeAll
    fun compileNeoForgeBranches(@TempDir(cleanup = CleanupMode.ON_SUCCESS) fixtureDirectory: Path) {
        directory = fixtureDirectory
        val fixture = BootstrapFixture(directory)
        fixture.create(kotlin = false, composite = false)
        fixture.write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)

        declarations.forEach { (name, declaration) ->
            fixture.write("src/main/java/fixture/$name.java", """
                package fixture;
                public final class $name {
                    //? if neoforge {
                    /*$declaration
                    *///?}
                }
            """)
        }

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

        fixture.run(
            ":26.3-fabric:compileJava", ":26.3-neoforge:compileClientJava",
            ":26.3-neoforge:writeCheckerClasspath",
        )

        declarations.keys.forEach { name ->
            // Inspect bytecode as well as successful compilation to prevent an empty preprocessed control.
            val bytecode = Files.readAllBytes(classFile("main", name))
            assertEquals(69, ((bytecode[6].toInt() and 255) shl 8) or (bytecode[7].toInt() and 255))
            assertTrue(String(bytecode, Charsets.ISO_8859_1).contains("net/minecraft/client/Minecraft"), name)

            val fabricClass = directory.resolve("versions/26.3-fabric/build/classes/java/main/fixture/$name.class")
            val fabricBytecode = String(Files.readAllBytes(fabricClass), Charsets.ISO_8859_1)
            assertEquals(false, fabricBytecode.contains("net/minecraft/client/Minecraft"), name)
        }
    }

    @Test
    fun `Forbidden APIs rejects dormant calls and raw signatures but misses generics and casts`() {
        val classpathFile = directory.resolve("versions/26.3-neoforge/build/checker-classpath.txt")
        val classpath = Files.readString(classpathFile).split(File.pathSeparator)
            .map { Path.of(it).toUri().toURL() }.toTypedArray()

        URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()).use { loader ->
            declarations.keys.forEach { name ->
                val expectedRejection = name in setOf("DormantBody", "FieldType", "ReturnType", "ParameterType")
                assertEquals(expectedRejection, forbiddenApisRejects(classFile("main", name), loader), name)
            }
            assertEquals(false, forbiddenApisRejects(classFile("main", "Common"), loader))
        }
    }

    @Test
    fun `ArchUnit rejects dormant calls and generic signatures but misses casts`() {
        ArchConfiguration.withThreadLocalScope { configuration ->
            configuration.setResolveMissingDependenciesFromClassPath(false)

            val rule = noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("net.minecraft.client..")

            declarations.keys.forEach { name ->
                val classes = ClassFileImporter().importPath(classFile("main", name))
                assertEquals(name != "CastOnly", rule.evaluate(classes).hasViolation(), name)
            }
            val common = ClassFileImporter().importPath(classFile("main", "Common"))
            assertEquals(false, rule.evaluate(common).hasViolation())

            // Client output is outside the proposed main-only scan, even though it contains client dependencies.
            val client = ClassFileImporter().importPath(classFile("client", "ClientOnly"))
            assertTrue(rule.evaluate(client).hasViolation())
        }
    }

    private fun classFile(sourceSet: String, name: String): Path {
        return directory.resolve("versions/26.3-neoforge/build/classes/java/$sourceSet/fixture/$name.class")
    }

    @Test
    fun `JDK jdeps reports every retained common client reference including casts and generics`() {
        val checker = ToolProvider.findFirst("jdeps").orElseThrow()

        (declarations.keys + "Common" + "ClientOnly").forEach { name ->
            val output = StringWriter()
            val errors = StringWriter()
            val sourceSet = if (name == "ClientOnly") "client" else "main"
            val result = checker.run(
                PrintWriter(output), PrintWriter(errors), "-verbose:class", "-filter:none",
                "-regex", "net\\.minecraft\\.client\\..*", classFile(sourceSet, name).toString(),
            )

            assertEquals(0, result, errors.toString())
            assertEquals("", errors.toString())
            assertEquals(name != "Common", output.toString().contains("net.minecraft.client.Minecraft"), name)
        }
    }

    @Test
    fun `NeoForge-only check rejects all controls while accepting client output and reusing configuration`(
        @TempDir(cleanup = CleanupMode.ON_SUCCESS) consumerDirectory: Path,
    ) {
        val fixture = createNeoForgeConsumer(consumerDirectory)
        fixture.run("buildAll")
        fixture.run("buildAll", "--configuration-cache")
        val reused = fixture.run("buildAll", "--configuration-cache")
        assertTrue(reused.output.contains("Configuration cache entry reused"), reused.output)
        assertEquals(TaskOutcome.SUCCESS, reused.task(":26.3-neoforge:checkCommonSide")?.outcome)

        declarations.forEach { (name, declaration) ->
            fixture.write("src/main/java/fixture/$name.java", """
                package fixture;
                public final class $name {
                    //? if neoforge {
                    /*$declaration
                    *///?}
                }
            """)
        }

        val failure = fixture.run("buildAll", "--configuration-cache", fails = true)
        assertEquals(TaskOutcome.FAILED, failure.task(":26.3-neoforge:checkCommonSide")?.outcome)
        assertTrue(failure.output.contains("Forbidden physical-client dependencies in common output"), failure.output)
        declarations.keys.forEach { name ->
            assertTrue(failure.output.contains("fixture.$name"), failure.output)
        }
    }

    @Test
    fun `NeoForge check permits empty common Java output`(
        @TempDir(cleanup = CleanupMode.ON_SUCCESS) consumerDirectory: Path,
    ) {
        val fixture = createNeoForgeConsumer(consumerDirectory)
        Files.delete(consumerDirectory.resolve("src/main/java/fixture/Common.java"))
        fixture.write("src/client/java/fixture/ClientOnly.java", """
            package fixture;
            public final class ClientOnly {
                public static net.minecraft.client.Minecraft game() { return null; }
            }
        """)

        val result = fixture.run("buildAll")
        assertEquals(TaskOutcome.NO_SOURCE, result.task(":26.3-neoforge:checkCommonSide")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":26.3-neoforge:compileClientJava")?.outcome)
    }

    private fun createNeoForgeConsumer(consumerDirectory: Path): BootstrapFixture {
        val fixture = BootstrapFixture(consumerDirectory)
        fixture.create(kotlin = false, composite = false)
        val manifest = consumerDirectory.resolve("mod.toml")
        Files.writeString(
            manifest,
            Files.readString(manifest).replace("[\"fabric\", \"neoforge\"]", "[\"neoforge\"]")
                .replace("26.3-fabric", "26.3-neoforge"),
        )
        fixture.write("stonecutter.gradle", """
            plugins { id "dev.bvengo.modkit.controller" }
            stonecutter.active null
        """)

        return fixture
    }

    private fun forbiddenApisRejects(classFile: Path, loader: ClassLoader): Boolean {
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
        checker.parseSignaturesString("net.minecraft.client.**")
        checker.addClassToCheck(classFile.toFile())

        return try {
            checker.run()
            false
        } catch (failure: ForbiddenApiException) {
            assertTrue(errors.any { it.contains("net.minecraft.client.Minecraft") }, errors.joinToString("\n"))
            true
        }
    }
}
