package com.homeassistant.adapter.outbound.runtime.distribution

import com.homeassistant.adapter.outbound.runtime.distribution.downloader.AssetDownloader
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat
import kotlin.io.DEFAULT_BUFFER_SIZE

internal abstract class DistributionInstaller(
    private val manifest: DistributionManifest,
    private val downloader: AssetDownloader,
    private val isSupportedPlatform: () -> Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun install(distributionRoot: Path): Path {
        // supported platform
        check(isSupportedPlatform()) {
            "Managed ${manifest.productName} setup supports Windows 10/11 x86-64 only"
        }

        // check already installed
        val (absoluteRoot, versionRoot) = resolveInstallPaths(distributionRoot)
        if (isInstalled(versionRoot)) return manifest.resolveEntryPoint(absoluteRoot)

        Files.createDirectories(absoluteRoot)
        checkPrerequisites(absoluteRoot)

        // download
        val temporaryRoot = Files.createTempDirectory(absoluteRoot, ".install-${manifest.version}-")
        try {
            val downloadedAsset = temporaryRoot.resolve("${manifest.productName}_v${manifest.version}.partial")
            log.info("Downloading ${manifest.productName}_v${manifest.version}")
            downloader.download(manifest.assetUri, downloadedAsset)

            log.info("Verify checksum")
            verifyChecksum(downloadedAsset)

            val stagedRoot = temporaryRoot.resolve("staged")
            Files.createDirectories(stagedRoot)

            log.info("Staging installation..")
            stageInstallation(downloadedAsset, stagedRoot)

            log.info("Checking installation..")
            checkInstallation(stagedRoot)

            log.info("Writing..")
            Files.writeString(stagedRoot.resolve(INSTALL_MARKER), manifest.assetSha256)

            log.info("Publishing..")
            publish(stagedRoot, versionRoot)

            log.info("Checking whether artifact is properly published..")
            check(isInstalled(versionRoot)) {
                "${manifest.productName} install directory exists but is incomplete: $versionRoot"
            }

            log.info("${manifest.productName} installed at $versionRoot successfully")

            return manifest.resolveEntryPoint(absoluteRoot)
        } finally {
            log.info("Remove temporary files")
            deleteTree(temporaryRoot)
        }
    }

    private fun resolveInstallPaths(distributionRoot: Path): Pair<Path, Path> {
        val absDistRoot = if(distributionRoot.isAbsolute) distributionRoot else distributionRoot.toAbsolutePath().normalize()
        val absVersionRoot = absDistRoot.resolve(manifest.version)
        return Pair(absDistRoot, absVersionRoot)
    }


    private fun isInstalled(installationRoot: Path): Boolean {
        val marker = installationRoot.resolve(INSTALL_MARKER)
        if (!Files.isRegularFile(marker)) return false
        if (!Files.readString(marker).trim().equals(manifest.assetSha256, ignoreCase = true)) return false

        return try {
            checkInstallation(installationRoot)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun verifyChecksum(downloadedAsset: Path) {
        val actualSha256 = sha256(downloadedAsset)
        check(actualSha256.equals(manifest.assetSha256, ignoreCase = true)) {
            "${manifest.productName} asset checksum mismatch: " +
                "expected=${manifest.assetSha256} actual=$actualSha256"
        }
    }

    private fun checkPrerequisites(distributionRoot: Path) {
        val availableSpace = Files.getFileStore(distributionRoot).usableSpace
        check(availableSpace >= manifest.minimumFreeSpaceBytes) {
            "Not enough disk space to install ${manifest.productName} v${manifest.version}: requiredBytes=${manifest.minimumFreeSpaceBytes} availableBytes=$availableSpace"
        }
    }

    /**
     * Prepares [downloadedAsset] as an installation under [stagedRoot].
     *
     * [stagedRoot] is an existing empty directory. Implementations may extract, copy, or move the
     * downloaded asset as needed, but must not publish it to the final version root.
     */
    protected abstract fun stageInstallation(downloadedAsset: Path, stagedRoot: Path)

    /**
     * Checks that [installationRoot] contains a complete installation.
     *
     * This check does not include the installation marker managed by this installer. Implementations
     * must throw [IllegalStateException] when required files or directories are missing and must not
     * modify the installation.
     */
    protected abstract fun checkInstallation(installationRoot: Path)

    private fun publish(stagedRoot: Path, versionRoot: Path) {
        try {
            Files.move(stagedRoot, versionRoot, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(stagedRoot, versionRoot)
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach {
                Files.deleteIfExists(it)
            }
        }
    }

    private companion object {
        const val INSTALL_MARKER = ".installed-sha256"
    }
}
