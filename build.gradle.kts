import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
val kotlinLoggingVersion = "2.1.21"
val slf4jVersion = "2.0.3"
val graphqlJavaVersion = "23.1"
val klerkVersion = "2026a39c06"
val ktorVersion = "3.2.3"
val jacksonVersion = "2.19.1"
plugins {
    kotlin("jvm") version "2.3.10"
    `java-library`
    `maven-publish`
}
group = "dev.klerkframework"
version = "1.0.0-alpha.2-SNAPSHOT"
dependencies {
    implementation("dev.klerkframework:klerk:$klerkVersion")
    api("com.graphql-java:graphql-java:$graphqlJavaVersion")
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("io.github.microutils:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation("org.slf4j:slf4j-simple:${slf4jVersion}")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-rc02")
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
kotlin {
    jvmToolchain(17)
    explicitApi = ExplicitApiMode.Strict
}
