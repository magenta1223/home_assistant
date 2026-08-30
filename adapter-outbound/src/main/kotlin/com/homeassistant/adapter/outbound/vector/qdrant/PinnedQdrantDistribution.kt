package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.runtime.distribution.DistributionManifest
import java.net.URI
import java.nio.file.Path

internal object PinnedQdrantDistribution {
    val manifest = DistributionManifest(
        productName = "qdrant",
        version = "1.19.0",
        assetUri = URI.create(
            "https://github.com/qdrant/qdrant/releases/download/v1.19.0/qdrant-x86_64-pc-windows-msvc.zip",
        ),
        assetSha256 = "980cb2e1ae771155cf211da8c0a8a9206b6482bd4effdc4db994d3adb707b087",
        assetSizeBytes = ASSET_SIZE_BYTES,
        minimumFreeSpaceBytes = ASSET_SIZE_BYTES + INSTALLED_SIZE_BYTES,
        entryPoint = Path.of("qdrant.exe"),
    )

    private const val ASSET_SIZE_BYTES = 29_340_688L
    private const val INSTALLED_SIZE_BYTES = 84_184_640L
}