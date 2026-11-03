package com.homeassistant.application.port.output.group

interface PendingComponentSource {
    fun pending(limit: Int): List<PendingComponent>
}