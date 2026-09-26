plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "org.eyman_"
version = "0.1.0"
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
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    compileOnly("org.jetbrains:annotations:26.0.1")

    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.24")

    compileOnly("me.clip:placeholderapi:2.11.6")

    implementation("com.alphacephei:vosk:0.3.45")
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")

    // Opus decoder for Simple Voice Chat audio. NOTE: VoicechatApi#createDecoder() returns null
    // on the Bukkit/Paper build of Simple Voice Chat (no Opus natives bundled for that platform -
    // see that method's own javadoc). opus4j is the same underlying native Opus wrapper SVC
    // itself is built on, published by the same author to the maven.maxhenkel.de repo above.
    implementation("de.maxhenkel.opus4j:opus4j:2.1.0")

    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    implementation("com.google.code.gson:gson:2.11.0")

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

    minimize {
        exclude(dependency("com.alphacephei:vosk:.*"))
        exclude(dependency("net.java.dev.jna:.*:.*"))
        exclude(dependency("de.maxhenkel.opus4j:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
