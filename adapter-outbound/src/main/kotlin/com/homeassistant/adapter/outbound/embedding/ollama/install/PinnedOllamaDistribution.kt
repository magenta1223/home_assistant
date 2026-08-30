package com.homeassistant.adapter.outbound.embedding.ollama.install

import com.homeassistant.adapter.outbound.runtime.distribution.DistributionManifest
import java.net.URI
import java.nio.file.Path

internal object PinnedOllamaDistribution {
    val manifest = DistributionManifest(
        productName = "ollama",
        version = "0.30.8",
        assetUri = URI.create(
            "https://github.com/ollama/ollama/releases/download/v0.30.8/ollama-windows-amd64.zip",
        ),
        assetSha256 = "c2d26d97e698027329c252629d7113bbc05d874b49960cbb03e93a39ae9fd95c",
        assetSizeBytes = ASSET_SIZE_BYTES,
        minimumFreeSpaceBytes = ASSET_SIZE_BYTES + INSTALLED_SIZE_BYTES,
        entryPoint = Path.of("ollama.exe"),
    )

    private const val ASSET_SIZE_BYTES = 1_456_561_126L
    private const val INSTALLED_SIZE_BYTES = 1_941_207_256L
}