plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    // Mosaic dropped its own Gradle plugin after 0.12; modern Mosaic uses the
    // official Kotlin Compose compiler plugin (version tracks the Kotlin version).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    application
}

group = "com.devhub"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Terminal UI
    implementation("com.jakewharton.mosaic:mosaic-runtime:0.18.0")
    implementation("com.github.ajalt.mordant:mordant:3.0.1")

    // HTTP (GitHub GraphQL + REST, SonarCloud REST)
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // Serialization / coroutines / time
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Ktor logs via SLF4J; provide a no-op backend to silence the "no providers" warning.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.devhub.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
