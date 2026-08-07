package com.homeassistant.adapter.inbound.http

import com.homeassistant.domain.identity.UserId
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

internal val TEST_HTTP_API_KEYS: Map<String, UserId> =
    HttpApiKeyConfig.fromJson("""[{"userId":"dad","token":"test-token"}]""")

internal fun HttpRequestBuilder.authenticateAsTestUser() {
    header(HttpHeaders.Authorization, "Bearer test-token")
}
