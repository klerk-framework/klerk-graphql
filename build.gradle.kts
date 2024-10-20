import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

val kotlinVersion = "2.0.21"
val kotlinLoggingVersion = "2.1.21"
val graphqlVersion = "7.1.1"
val ktorVersion = "2.3.10"
val klerkVersion = "1.0.0-beta.3"

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
}

group = "dev.klerkframework"
version = "1.0.0-alpha.1"


dependencies {
    implementation("com.github.klerk-framework:klerk:$klerkVersion")
    implementation("com.expediagroup:graphql-kotlin-ktor-server:$graphqlVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}

publishing {
    publications {
        create<MavenPublication>("Maven") {
            artifactId = "klerk-graphql"
            from(components["java"])
        }
    }
}

java {
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}
