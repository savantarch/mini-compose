plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "com.savantarch"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.savantarch.minicompose.MiniComposeKt")
}
