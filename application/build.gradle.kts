plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation("io.mockk:mockk:1.13.13") // 최신 버전에 맞게 수정 가능
}
