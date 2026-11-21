plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    inputs.files(rootProject.subprojects.map { it.fileTree("src/main/kotlin") })
        .withPropertyName("productionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
