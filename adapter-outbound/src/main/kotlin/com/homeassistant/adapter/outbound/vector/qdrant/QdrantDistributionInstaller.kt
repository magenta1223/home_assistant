package com.homeassistant.adapter.outbound.vector.qdrant

import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.zip.ZipInputStream

internal data class QdrantDistributionManifest(
    val version: String,
    val assetUri: URI,
    val assetSha256: String,
    val assetSizeBytes: Long,
)

internal object PinnedQdrantDistribution {
    val manifest = QdrantDistributionManifest(
        version = "1.19.0",
        assetUri = URI.create(
            "https://github.com/qdrant/qdrant/releases/download/v1.19.0/qdrant-x86_64-pc-windows-msvc.zip",
        ),
        assetSha256 = "980cb2e1ae771155cf211da8c0a8a9206b6482bd4effdc4db994d3adb707b087",
        assetSizeBytes = 29_340_688L,
    )
}

internal fun interface QdrantAssetDownloader {
    fun download(source: URI, target: Path)
}

internal class HttpQdrantAssetDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : QdrantAssetDownloader {
    override fun download(source: URI, target: Path) {
        val response = client.send(
            HttpRequest.newBuilder(source).timeout(Duration.ofMinutes(10)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        response.body().use { input ->
            check(response.statusCode() in 200..299) {
                "Qdrant download failed: HTTP ${response.statusCode()}"
            }
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use(input::copyTo)
        }
    }
}

internal class QdrantDistributionInstaller(
    private val manifest: QdrantDistributionManifest = PinnedQdrantDistribution.manifest,
    private val downloader: QdrantAssetDownloader = HttpQdrantAssetDownloader(),
    private val isSupportedPlatform: () -> Boolean = ::isSupportedWindowsPlatform,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun install(runtimeRoot: Path): Path {
        check(isSupportedPlatform()) {
            "Managed Qdrant setup supports Windows 10/11 x86-64 only"
        }
        val normalizedRoot = runtimeRoot.toAbsolutePath().normalize()
        Files.createDirectories(normalizedRoot)
        val installDirectory = normalizedRoot.resolve(manifest.version)
        if (isCompleteInstall(installDirectory)) return executableAt(installDirectory)

        val requiredSpace = manifest.assetSizeBytes * 2
        val availableSpace = Files.getFileStore(normalizedRoot).usableSpace
        check(availableSpace >= requiredSpace) {
            "Not enough disk space to install Qdrant ${manifest.version}: " +
                "requiredBytes=$requiredSpace availableBytes=$availableSpace"
        }

        val stagingDirectory = Files.createTempDirectory(normalizedRoot, ".install-${manifest.version}-")
        try {
            val archive = stagingDirectory.resolve("qdrant-windows.zip.partial")
            log.info("Downloading Qdrant {} from official release", manifest.version)
            downloader.download(manifest.assetUri, archive)
            val actualSha256 = sha256(archive)
            check(actualSha256.equals(manifest.assetSha256, ignoreCase = true)) {
                "Qdrant archive checksum mismatch: expected=${manifest.assetSha256} actual=$actualSha256"
            }

            val extracted = stagingDirectory.resolve("extracted")
            Files.createDirectories(extracted)
            extractZip(archive, extracted)
            check(Files.isRegularFile(executableAt(extracted))) {
                "Qdrant archive does not contain qdrant.exe"
            }
            Files.writeString(extracted.resolve(INSTALL_MARKER), manifest.assetSha256)

            if (!Files.exists(installDirectory)) moveDirectory(extracted, installDirectory)
            check(isCompleteInstall(installDirectory)) {
                "Qdrant install directory exists but is incomplete: $installDirectory"
            }
            log.info("Qdrant {} installed at {}", manifest.version, installDirectory)
            return executableAt(installDirectory)
        } finally {
            deleteTree(stagingDirectory)
        }
    }

    fun executable(runtimeRoot: Path): Path =
        executableAt(runtimeRoot.toAbsolutePath().normalize().resolve(manifest.version))

    private fun isCompleteInstall(directory: Path): Boolean {
        val marker = directory.resolve(INSTALL_MARKER)
        return Files.isRegularFile(executableAt(directory)) &&
            Files.isRegularFile(marker) &&
            Files.readString(marker).trim().equals(manifest.assetSha256, ignoreCase = true)
    }

    private fun executableAt(directory: Path): Path = directory.resolve("qdrant.exe")

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun extractZip(archive: Path, target: Path) {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = target.resolve(entry.name).normalize()
                check(destination.startsWith(target)) { "Unsafe path in Qdrant archive: ${entry.name}" }
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
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private companion object {
        const val INSTALL_MARKER = ".installed-sha256"

        fun isSupportedWindowsPlatform(): Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
                System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")
    }
}
