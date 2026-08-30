package com.homeassistant.adapter.outbound.embedding.ollama.install

import com.homeassistant.adapter.outbound.runtime.distribution.DistributionInstaller
import com.homeassistant.adapter.outbound.runtime.distribution.DistributionManifest
import com.homeassistant.adapter.outbound.runtime.distribution.WindowsX64Platform
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.AssetDownloader
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.HttpAssetDownloader
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

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

    private fun extractZip(archive: Path, target: Path) {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = target.resolve(entry.name).normalize()
                check(destination.startsWith(target)) { "Unsafe path in Ollama archive: ${entry.name}" }
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(zip, destination)
                }
                zip.closeEntry()
            }
        }
    }
}
