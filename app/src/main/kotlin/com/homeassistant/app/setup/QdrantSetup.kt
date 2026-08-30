package com.homeassistant.app.setup

import com.homeassistant.adapter.outbound.vector.qdrant.QdrantRuntimeSetup

fun main() {
    val executable = QdrantRuntimeSetup.prepare()
    println("Managed Qdrant is ready at $executable")
}
