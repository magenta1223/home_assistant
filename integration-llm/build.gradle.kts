plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.schema.generator.json)
    implementation(libs.logback.classic)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
}
