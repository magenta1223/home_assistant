package com.homeassistant.adapter.outbound.embedding.ollama.install

import com.homeassistant.adapter.outbound.runtime.distribution.DistributionInstaller
import com.homeassistant.adapter.outbound.runtime.distribution.DistributionManifest
import com.homeassistant.adapter.outbound.runtime.distribution.WindowsX64Platform
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.AssetDownloader
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.HttpAssetDownloader
import java.nio.file.Files
import java.nio.file.Path

internal class OllamaDistributionInstaller(
    manifest: DistributionManifest = PinnedOllamaDistribution.manifest,
    downloader: AssetDownloader = HttpAssetDownloader(),
    isSupportedPlatform: () -> Boolean = WindowsX64Platform::isSupported,
) : DistributionInstaller(manifest, downloader, isSupportedPlatform) {

    override fun stageInstallation(downloadedAsset: Path, stagedRoot: Path) {
        extractZip(downloadedAsset, stagedRoot)
    }

    override fun checkInstallation(installationRoot: Path) {
        check(Files.isRegularFile(installationRoot.resolve("ollama.exe"))) {
            "Ollama archive does not contain ollama.exe"
        }
        check(Files.isDirectory(installationRoot.resolve("lib/ollama"))) {
            "Ollama archive does not contain lib/ollama"
        }
    }
}
