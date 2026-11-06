package com.homeassistant.application.port.outbound.memoryextraction.group

/** Components not yet placed in any Group. */
interface PendingComponentSource {
    /** Up to [limit] unplaced Components in Component id order. */
    fun pending(limit: Int): List<PendingComponent>
}
