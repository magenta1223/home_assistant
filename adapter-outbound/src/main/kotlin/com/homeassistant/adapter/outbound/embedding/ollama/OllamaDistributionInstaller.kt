package com.homeassistant.adapter.outbound.embedding.ollama

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

internal data class OllamaDistributionManifest(
    val version: String,
    val assetUri: URI,
    val assetSha256: String,
    val assetSizeBytes: Long,
)

internal object PinnedOllamaDistribution {
    val manifest = OllamaDistributionManifest(
        version = "0.30.8",
        assetUri = URI.create(
            "https://github.com/ollama/ollama/releases/download/v0.30.8/ollama-windows-amd64.zip",
        ),
        assetSha256 = "c2d26d97e698027329c252629d7113bbc05d874b49960cbb03e93a39ae9fd95c",
        assetSizeBytes = 1_456_561_126L,
    )
}

internal fun interface OllamaAssetDownloader {
    fun download(source: URI, target: Path)
}

internal class HttpOllamaAssetDownloader(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : OllamaAssetDownloader {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun download(source: URI, target: Path) {
        val request = HttpRequest.newBuilder(source)
            .timeout(Duration.ofHours(1))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            check(response.statusCode() in 200..299) {
                "Ollama download failed: HTTP ${response.statusCode()}"
            }
            val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                var nextProgress = 10
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (contentLength > 0) {
                        val progress = (downloaded * 100 / contentLength).toInt()
                        if (progress >= nextProgress) {
                            log.info("Ollama download progress={}%, bytes={}", progress, downloaded)
                            nextProgress += 10
                        }
                    }
                }
            }
        }
    }
}

internal class OllamaDistributionInstaller(
    private val manifest: OllamaDistributionManifest = PinnedOllamaDistribution.manifest,
    private val downloader: OllamaAssetDownloader = HttpOllamaAssetDownloader(),
    private val isSupportedPlatform: () -> Boolean = ::isSupportedWindowsPlatform,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun install(runtimeRoot: Path): Path {
        check(isSupportedPlatform()) {
            "Managed Ollama setup supports Windows 10/11 x86-64 only"
        }
        val normalizedRoot = runtimeRoot.toAbsolutePath().normalize()
        Files.createDirectories(normalizedRoot)
        val installDirectory = normalizedRoot.resolve(manifest.version)
        if (isCompleteInstall(installDirectory)) return executableAt(installDirectory)

        val requiredSpace = manifest.assetSizeBytes * 2
        val availableSpace = Files.getFileStore(normalizedRoot).usableSpace
        check(availableSpace >= requiredSpace) {
            "Not enough disk space to install Ollama ${manifest.version}: " +
                "requiredBytes=$requiredSpace availableBytes=$availableSpace"
        }

        val stagingDirectory = Files.createTempDirectory(normalizedRoot, ".install-${manifest.version}-")
        try {
            val archive = stagingDirectory.resolve("ollama-windows-amd64.zip.partial")
            log.info("Downloading Ollama {} from official release", manifest.version)
            downloader.download(manifest.assetUri, archive)
            val actualSha256 = sha256(archive)
            check(actualSha256.equals(manifest.assetSha256, ignoreCase = true)) {
                "Ollama archive checksum mismatch: expected=${manifest.assetSha256} actual=$actualSha256"
            }

            val extracted = stagingDirectory.resolve("extracted")
            Files.createDirectories(extracted)
            extractZip(archive, extracted)
            check(Files.isRegularFile(executableAt(extracted))) {
                "Ollama archive does not contain ollama.exe"
            }
            check(Files.isDirectory(libraryDirectoryAt(extracted))) {
                "Ollama archive does not contain lib/ollama"
            }
            Files.writeString(extracted.resolve(INSTALL_MARKER), manifest.assetSha256)

            if (!Files.exists(installDirectory)) {
                moveDirectory(extracted, installDirectory)
            }
            check(isCompleteInstall(installDirectory)) {
                "Ollama install directory exists but is incomplete: $installDirectory"
            }
            log.info("Ollama {} installed at {}", manifest.version, installDirectory)
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
            Files.isDirectory(libraryDirectoryAt(directory)) &&
            Files.isRegularFile(marker) &&
            Files.readString(marker).trim().equals(manifest.assetSha256, ignoreCase = true)
    }

    private fun executableAt(directory: Path): Path = directory.resolve("ollama.exe")

    private fun libraryDirectoryAt(directory: Path): Path = directory.resolve("lib/ollama")

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
