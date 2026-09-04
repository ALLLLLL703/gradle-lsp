plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "xyz.al.gradlelsp"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-assignment-compiler-plugin-embeddable:2.4.10")
    implementation("org.gradle:gradle-tooling-api:9.7.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test-junit5"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.add("org.jetbrains.kotlin.K1Deprecation")
        optIn.add("org.jetbrains.kotlin.config.CompilerConfiguration.Internals")
    }
}

application {
    mainClass = "xyz.al.gradlelsp.MainKt"
}

tasks.test {
    useJUnitPlatform()
}
