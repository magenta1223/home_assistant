package com.homeassistant.app.vector

import com.homeassistant.adapter.outbound.vector.qdrant.QdrantRuntimeSetup

fun main() {
    val executable = QdrantRuntimeSetup.prepare()
    println("Managed Qdrant is ready at $executable")
}
