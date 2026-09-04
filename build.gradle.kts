plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "xyz.al.gradlelsp"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
    exclusiveContent {
        forRepository {
            maven("https://jitpack.io")
        }
        filter {
            includeGroup("com.github.java-decompiler")
        }
    }
}

val kotlinStdlibSources = configurations.create("kotlinStdlibSources")

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("com.github.java-decompiler:jd-core:v1.1.3")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin-embeddable:2.4.10")
    implementation("org.jetbrains.kotlin:kotlin-assignment-compiler-plugin-embeddable:2.4.10")
    implementation("org.gradle:gradle-tooling-api:9.7.1")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test-junit5"))
    add(kotlinStdlibSources.name, "org.jetbrains.kotlin:kotlin-stdlib:2.4.10:sources")
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
    applicationDefaultJvmArgs = listOf(
        "-Xms64m",
        "-Xmx512m",
        "-Xss512k",
        "-XX:+UseSerialGC",
        "-XX:MaxMetaspaceSize=256m",
        "-XX:CompressedClassSpaceSize=64m",
        "-XX:MaxDirectMemorySize=64m",
        "-XX:ReservedCodeCacheSize=64m",
    )
}

tasks.test {
    useJUnitPlatform()
    classpath += kotlinStdlibSources
}
