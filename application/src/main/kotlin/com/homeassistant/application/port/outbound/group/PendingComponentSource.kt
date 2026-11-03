package com.homeassistant.application.port.outbound.group

interface PendingComponentSource {
    fun pending(limit: Int): List<PendingComponent>
}