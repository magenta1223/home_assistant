plugins {
    kotlin("jvm") version "2.2.21"
}

group = "com.homeassistant.integrationcodex"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}