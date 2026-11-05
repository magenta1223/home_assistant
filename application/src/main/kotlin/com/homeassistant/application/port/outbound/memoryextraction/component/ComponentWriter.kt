package com.homeassistant.application.port.outbound.memoryextraction.component

interface ComponentWriter {
    /**
     * Marks [episodeId] as extracted and stores [statements] as its Components in one write; returns their ids in order.
     * An episode with no long-term knowledge is still marked, so it is never extracted again.
     */
    fun writeComponents(episodeId: Int, statements: List<String>): List<Int>

}