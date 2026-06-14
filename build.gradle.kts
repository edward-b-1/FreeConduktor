plugins {
    kotlin("jvm") version "2.2.0"
    id("org.openjfx.javafxplugin") version "0.1.0"
    application
}

group = "com.freeconductor"
version = "0.1.14"

repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven/")
}

dependencies {
    // Kafka
    implementation("org.apache.kafka:kafka-clients:3.6.1")

    // Avro
    implementation("org.apache.avro:avro:1.11.3")

    // HTTP + JSON for REST APIs
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.1")

    // AtlantaFX theming
    implementation("io.github.mkpaz:atlantafx-base:2.0.1")

    // Icons
    implementation("org.kordamp.ikonli:ikonli-javafx:12.3.1")
    implementation("org.kordamp.ikonli:ikonli-fontawesome5-pack:12.3.1")

    // CSV parsing
    implementation("org.apache.commons:commons-csv:1.10.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.10")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Coroutines for background tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.7.3")
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web", "javafx.swing")
}

application {
    mainClass.set("com.freeconductor.AppKt")
    applicationDefaultJvmArgs = listOf(
        "--add-opens=javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
        "--add-opens=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED"
    )
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// ── Packaging ─────────────────────────────────────────────────────────────────
// Produces a self-contained app image (portable zip) or MSI installer.
// Both bundle a private JRE — no Java installation required on the target machine.
//
// Usage:
//   ./gradlew jpackageImage   → build/jpackage/FreeConduktor/  (portable, zip for GitHub)
//   ./gradlew jpackageMsi     → build/jpackage/FreeConduktor-<ver>.msi  (requires WiX 3)
//
// Prerequisites:
//   - JDK 21+ bin directory on PATH  (jpackage lives there)
//   - For MSI only: WiX Toolset 3.x  https://github.com/wixtoolset/wix3/releases

val jpackageInputDir = layout.buildDirectory.dir("install/FreeConduktor/lib")
val jpackageOutputDir = layout.buildDirectory.dir("jpackage")
val appVersion = project.version.toString()

fun jpackageBaseArgs(type: String) = listOf(
    "jpackage",
    "--type",         type,
    "--name",         "FreeConduktor",
    "--app-version",  appVersion,
    "--input",        jpackageInputDir.get().asFile.absolutePath,
    "--main-jar",     "FreeConduktor-${appVersion}.jar",
    "--main-class",   "com.freeconductor.AppKt",
    "--java-options", "--add-opens=javafx.graphics/com.sun.javafx.css=ALL-UNNAMED",
    "--java-options", "--add-opens=javafx.controls/com.sun.javafx.scene.control=ALL-UNNAMED",
    "--icon",         "src/main/resources/com/freeconductor/icons/free-conduktor.ico",
    "--dest",         jpackageOutputDir.get().asFile.absolutePath
)

tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Creates a portable self-contained app image (no installer needed)"
    dependsOn("installDist")
    doFirst {
        val out = jpackageOutputDir.get().asFile
        out.resolve("FreeConduktor").deleteRecursively()
        out.mkdirs()
    }
    commandLine(jpackageBaseArgs("app-image"))
}

tasks.register<Exec>("jpackageMsi") {
    group = "distribution"
    description = "Creates a Windows MSI installer (requires WiX Toolset 3)"
    dependsOn("installDist")
    doFirst { jpackageOutputDir.get().asFile.mkdirs() }
    commandLine(jpackageBaseArgs("msi"))
}
