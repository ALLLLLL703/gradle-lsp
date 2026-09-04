plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "xyz.al.gradlelsp"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")

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
