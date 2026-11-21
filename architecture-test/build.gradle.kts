plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlin.test)
}
