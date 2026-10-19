package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.runtime.distribution.DistributionInstaller
import com.homeassistant.adapter.outbound.runtime.distribution.DistributionManifest
import com.homeassistant.adapter.outbound.runtime.distribution.WindowsX64Platform
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.AssetDownloader
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.HttpAssetDownloader
import java.nio.file.Files
import java.nio.file.Path

internal class QdrantDistributionInstaller(
    manifest: DistributionManifest = PinnedQdrantDistribution.manifest,
    downloader: AssetDownloader = HttpAssetDownloader(),
    isSupportedPlatform: () -> Boolean = WindowsX64Platform::isSupported,
) : DistributionInstaller(manifest, downloader, isSupportedPlatform) {

    override fun stageInstallation(downloadedAsset: Path, stagedRoot: Path) {
        extractZip(downloadedAsset, stagedRoot)
    }

    override fun checkInstallation(installationRoot: Path) {
        check(Files.isRegularFile(installationRoot.resolve("qdrant.exe"))) {
            "Qdrant archive does not contain qdrant.exe"
        }
    }
}
