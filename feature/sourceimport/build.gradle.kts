plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":application"))
    api(project(":domain"))

    testImplementation(libs.kotlin.test)
}
