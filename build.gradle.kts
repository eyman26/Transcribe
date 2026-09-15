plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "org.eyman_"
version = "0.1.0-SNAPSHOT"
description = "Transcribe - Voice-activated spell casting for Paper, powered by Simple Voice Chat + Vosk STT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
    // ---- Paper API (compile-only, provided by the server at runtime) ----
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // ---- @NotNull / @Nullable annotations used throughout the codebase ----
    // Paper's own API transitively provides these, but declared explicitly for clean
    // compilation of non-Paper-API classes (engine package) that don't otherwise pull it in.
    compileOnly("org.jetbrains:annotations:26.0.1")

    // ---- Simple Voice Chat API (compile-only, provided by the SVC plugin at runtime) ----
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.5.27")

    // ---- PlaceholderAPI (soft dependency, compile-only) ----
    compileOnly("me.clip:placeholderapi:2.11.6")

    // ---- Vosk Java SDK (JNI-backed offline STT engine, shaded into the jar) ----
    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // ---- Java-WebSocket (external STT microservice client, shaded into the jar) ----
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // ---- JSON handling for grammar arrays / WS payloads (shaded into the jar) ----
    implementation("com.google.code.gson:gson:2.11.0")

    // ---- Tests ----
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("Transcribe")

    // Relocate shaded dependencies to avoid classpath collisions with other plugins
    relocate("org.java_websocket", "org.eyman_.libs.javawebsocket")
    relocate("com.google.gson", "org.eyman_.libs.gson")
    relocate("com.sun.jna", "org.eyman_.libs.jna")

    // Vosk's native JNI loader must NOT be relocated - it loads platform .so/.dll/.dylib
    // resources by fixed package-relative paths, so leave org.vosk untouched.

    minimize {
        exclude(dependency("com.alphacephei:vosk:.*"))
        exclude(dependency("net.java.dev.jna:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
