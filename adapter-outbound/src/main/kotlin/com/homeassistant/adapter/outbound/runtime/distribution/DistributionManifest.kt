package com.homeassistant.adapter.outbound.runtime.distribution

import java.net.URI
import java.nio.file.Path

internal data class DistributionManifest(
    val productName: String,
    val version: String,
    val assetUri: URI,
    val assetSha256: String,
    val assetSizeBytes: Long,
    val minimumFreeSpaceBytes: Long = 0L,
    val entryPoint: Path = Path.of(""),
    val requiredPaths: List<Path> = emptyList()
) {
    /** Resolves the installed entry point under [distributionRoot] for this pinned version. */
    fun resolveEntryPoint(distributionRoot: Path): Path =
        distributionRoot.resolve(version).resolve(entryPoint)
}
