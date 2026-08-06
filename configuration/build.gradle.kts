plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.dotenv.kotlin)

    testImplementation(libs.kotlin.test)
}
