package com.homeassistant.application.port.outbound.memoryextraction.group

interface PendingComponentSource {
    fun pending(limit: Int): List<PendingComponent>
}