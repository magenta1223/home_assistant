plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    api(project(":domain"))

    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
}
