plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":application"))
    api(project(":domain"))

    implementation(project(":common"))
    implementation(project(":configuration"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.slack.bolt.socket.mode)
    implementation(libs.javax.websocket.api)
    implementation(libs.tyrus.standalone.client)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
}
