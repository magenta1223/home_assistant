plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    api(project(":datamodel"))
    api(project(":domain"))

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
}
