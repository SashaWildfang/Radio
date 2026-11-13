import java.security.MessageDigest

plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.sasha"
version = "2.1.0"
description = "Lobby radio for Fractal Network: note block (.nbs) songs with a GUI. Built by Sasha Wildfang."

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    // Compiled against the oldest supported API so the jar runs on Paper 1.21.11 and every newer release.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Optional: only used when PlaceholderAPI is installed.
    compileOnly("me.clip:placeholderapi:2.11.6")
}

java {
    // Paper 1.21.11 requires Java 21; newer servers run on newer JVMs, which load Java 21 bytecode fine.
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked", "-Xlint:removal"))
    }

    processResources {
        val props = mapOf("version" to project.version, "description" to project.description)
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    runServer {
        minecraftVersion("1.21.11")
    }

    /**
     * Writes songs/index.json: every .nbs under songs/ with its size and SHA-1.
     * The plugin downloads this list to know which songs to fetch. Run after adding or changing songs.
     */
    register("songIndex") {
        group = "radio"
        description = "Regenerates songs/index.json from the songs folder."
        val songsDir = layout.projectDirectory.dir("songs").asFile
        doLast {
            val entries = songsDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("nbs", ignoreCase = true) }
                .sortedBy { it.relativeTo(songsDir).invariantSeparatorsPath.lowercase() }
                .map { file ->
                    val sha1 = MessageDigest.getInstance("SHA-1").digest(file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    val path = file.relativeTo(songsDir).invariantSeparatorsPath
                        .replace("\\", "\\\\").replace("\"", "\\\"")
                    """    {"path": "$path", "size": ${file.length()}, "sha1": "$sha1"}"""
                }
                .toList()
            songsDir.resolve("index.json").writeText(
                "{\n  \"songs\": [\n" + entries.joinToString(",\n") + "\n  ]\n}\n"
            )
            println("Wrote ${entries.size} songs to songs/index.json")
        }
    }
}
