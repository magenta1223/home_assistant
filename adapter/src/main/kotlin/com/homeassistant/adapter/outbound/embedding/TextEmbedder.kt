package com.homeassistant.adapter.outbound.embedding

interface TextEmbedder {
    fun embed(text: String): List<Float>
}
