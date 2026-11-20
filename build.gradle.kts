plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.bvengo"
version = "0.1.0"

repositories {
    maven("https://maven.kikugie.dev/releases")
    maven("https://maven.fabricmc.net/")
    maven("https://maven.neoforged.net/releases/")
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("dev.kikugie:stonecutter:0.9.8")
    implementation("net.fabricmc:fabric-loom:1.17.21")
    implementation("net.neoforged:moddev-gradle:2.0.147")
    implementation("org.tomlj:tomlj:1.1.1")

    testImplementation(gradleTestKit())
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

gradlePlugin {
    plugins {
        create("modkitSettings") {
            id = "dev.bvengo.modkit.settings"
            implementationClass = "dev.bvengo.modkit.settings.ModKitSettingsPlugin"
            displayName = "ModKit settings"
            description = "Registers explicit Minecraft targets with Stonecutter."
        }
        create("modkitController") {
            id = "dev.bvengo.modkit.controller"
            implementationClass = "dev.bvengo.modkit.controller.ModKitControllerPlugin"
            displayName = "ModKit controller"
            description = "Configures shared Stonecutter parameters and build aggregation."
        }
        create("modkitTarget") {
            id = "dev.bvengo.modkit.target"
            implementationClass = "dev.bvengo.modkit.target.ModKitTargetPlugin"
            displayName = "ModKit target"
            description = "Configures split-source Minecraft loader targets."
        }
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("functional", "runtime")
    }
}

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Tests real composite and file-backed Maven consumption with both loaders."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("functional")
    }
    systemProperty("modkit.projectDirectory", layout.projectDirectory.asFile.absolutePath)
    systemProperty("modkit.testRepository", layout.buildDirectory.dir("test-maven").get().asFile.absolutePath)
    maxHeapSize = "512m"
    testLogging {
        events("passed", "failed", "skipped")
    }
    dependsOn("publishAllPublicationsToTestRepository")
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(functionalTest)
}

tasks.register<Test>("runtimeTest") {
    description = "Launches real Minecraft clients and a temporary NeoForge server."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("runtime")
    }
    systemProperty("modkit.projectDirectory", layout.projectDirectory.asFile.absolutePath)
    systemProperty("modkit.testRepository", layout.buildDirectory.dir("test-maven").get().asFile.absolutePath)
    systemProperty("modkit.test.acceptEula", providers.gradleProperty("modkit.test.acceptEula").getOrElse("false"))
    maxHeapSize = "512m"
    testLogging {
        events("passed", "failed", "skipped")
    }
    dependsOn("publishAllPublicationsToTestRepository")
    outputs.upToDateWhen { false }
}

publishing {
    repositories {
        maven {
            name = "test"
            url = layout.buildDirectory.dir("test-maven").get().asFile.toURI()
        }
    }
}
