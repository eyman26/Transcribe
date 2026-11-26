plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

// one jar per target: ./gradlew shadowJar -Ptarget=26.2 (build-all.sh does all of them)
// override the paper api with -PpaperApi=26.2.build.135-stable
data class Target(val paperApi: String, val pluginApiVersion: String, val buildJdk: Int)

// 1.21.x: every stable release Paper publishes (Paper never shipped 1.21.2, it went straight to 1.21.3).
// The plain "1.21" target uses api-version 1.21, so that jar loads on any 1.21.x server.
val targets = linkedMapOf(
    "1.21" to Target("1.21-R0.1-SNAPSHOT", "1.21", 21),
    "1.21.1" to Target("1.21.1-R0.1-SNAPSHOT", "1.21.1", 21),
    "1.21.3" to Target("1.21.3-R0.1-SNAPSHOT", "1.21.3", 21),
    "1.21.4" to Target("1.21.4-R0.1-SNAPSHOT", "1.21.4", 21),
    "1.21.5" to Target("1.21.5-R0.1-SNAPSHOT", "1.21.5", 21),
    "1.21.6" to Target("1.21.6-R0.1-SNAPSHOT", "1.21.6", 21),
    "1.21.7" to Target("1.21.7-R0.1-SNAPSHOT", "1.21.7", 21),
    "1.21.8" to Target("1.21.8-R0.1-SNAPSHOT", "1.21.8", 21),
    "1.21.9" to Target("1.21.9-R0.1-SNAPSHOT", "1.21.9", 21),
    "1.21.10" to Target("1.21.10-R0.1-SNAPSHOT", "1.21.10", 21),
    "1.21.11" to Target("1.21.11-R0.1-SNAPSHOT", "1.21.11", 21),
    "26.1" to Target("26.1.2.build.+", "26.1", 25),
    "26.2" to Target("26.2.build.+", "26.2", 25),
    "26.3" to Target("26.3.build.+", "26.3", 25),
)

val targetId = (findProperty("target") as String?) ?: "26.2"
val target = targets[targetId]
    ?: throw GradleException("Unknown -Ptarget=$targetId. Valid targets: ${targets.keys.joinToString()}")
val paperApiCoordinate = (findProperty("paperApi") as String?) ?: target.paperApi

group = "org.eyman_"
version = "0.2"
description = "Transcribe - Voice-activated spell casting for Paper, powered by Simple Voice Chat + Vosk STT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(target.buildJdk))
    }
}

repositories {
    mavenCentral()

    // Paper API
    maven("https://repo.papermc.io/repository/maven-public/")

    // Simple Voice Chat API
    maven("https://maven.maxhenkel.de/repository/public")
    maven("https://api.modrinth.com/maven")

    // PlaceholderAPI
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")

    // Vosk Java bindings
    maven("https://jitpack.io")
}

dependencies {
    // paper 26.x needs JDK 25, 1.21.x needs JDK 21
    compileOnly("io.papermc.paper:paper-api:$paperApiCoordinate")

    compileOnly("org.jetbrains:annotations:26.0.1")

    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.24")

    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // SVC's createDecoder() returns null on the bukkit build, so we bring our own opus
    implementation("de.maxhenkel.opus4j:opus4j:2.1.0")

    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(target.buildJdk)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "apiVersion" to target.pluginApiVersion,
    )
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("mc$targetId")
    archiveBaseName.set("Transcribe")

    minimize {
        exclude(dependency("com.alphacephei:vosk:.*"))
        exclude(dependency("net.java.dev.jna:.*:.*"))
        exclude(dependency("de.maxhenkel.opus4j:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.register("printTargets") {
    group = "help"
    description = "Lists the supported build targets."
    doLast {
        targets.forEach { (id, t) ->
            println("%-6s paper-api=%-18s api-version=%-6s JDK %d".format(id, t.paperApi, t.pluginApiVersion, t.buildJdk))
        }
    }
}
