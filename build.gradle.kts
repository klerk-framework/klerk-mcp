import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.1.10"
    `maven-publish`
}

val klerkBomVersion = "1.0.0-beta.5"
val kotlinLoggingVersion = "2.1.21"
val klerkVersion = "a3640ba3a8"

group = "dev.klerkframework"
version = "0.1.0-SNAPSHOT"

dependencies {
    compileOnly("dev.klerkframework:klerk:$klerkVersion")

    compileOnly("io.ktor:ktor-server-core-jvm")
    compileOnly("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")


    // Kotlin standard libraries
//    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutines_version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serialization_version")}")

    api("io.modelcontextprotocol:kotlin-sdk:${property("mcp_sdk_version")}") // MCP SDK

    implementation("org.slf4j:slf4j-api:${property("slf4j_version")}")

    // Testing
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}


publishing {
    publications {
        create<MavenPublication>("Maven") {
            artifactId = "klerk-mcp"
            from(components["java"])
        }
    }
}


java {
    withSourcesJar()
}
