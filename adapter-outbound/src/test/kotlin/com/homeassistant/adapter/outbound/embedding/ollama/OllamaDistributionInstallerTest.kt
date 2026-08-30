package com.homeassistant.adapter.outbound.embedding.ollama

import com.homeassistant.adapter.outbound.embedding.ollama.install.OllamaDistributionInstaller
import com.homeassistant.adapter.outbound.runtime.distribution.DistributionManifest
import com.homeassistant.adapter.outbound.runtime.distribution.downloader.AssetDownloader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaDistributionInstallerTest {
    @Test
    fun `installs verified archive and reuses complete installation`() {
        withTempDirectory("ollama-installer") { workspace ->
            val archive = createArchive(workspace.resolve("ollama.zip"))
            var downloads = 0
            val installer = installer(archive) { source, target ->
                downloads++
                Files.copy(source, target)
            }

            val first = installer.install(workspace.resolve("runtime"))
            val second = installer.install(workspace.resolve("runtime"))

            assertEquals(first, second)
            assertTrue(Files.isRegularFile(first))
            assertEquals(1, downloads)
        }
    }

    @Test
    fun `rejects an archive whose checksum does not match`() {
        withTempDirectory("ollama-checksum") { workspace ->
            val archive = createArchive(workspace.resolve("ollama.zip"))
            val installer = OllamaDistributionInstaller(
                manifest = manifest(archive, "0".repeat(64)),
                downloader = AssetDownloader { source, target -> Files.copy(Path.of(source), target) },
                isSupportedPlatform = { true },
            )

            assertFailsWith<IllegalStateException> {
                installer.install(workspace.resolve("runtime"))
            }
            assertFalse(workspace.resolve("runtime/test-version").exists())
        }
    }

    @Test
    fun `cleans interrupted staging and succeeds on retry`() {
        withTempDirectory("ollama-retry") { workspace ->
            val archive = createArchive(workspace.resolve("ollama.zip"))
            var attempts = 0
            val installer = installer(archive) { source, target ->
                attempts++
                if (attempts == 1) {
                    Files.writeString(target, "partial")
                    error("download interrupted")
                }
                Files.copy(source, target)
            }

            assertFailsWith<IllegalStateException> {
                installer.install(workspace.resolve("runtime"))
            }
            assertFalse(workspace.resolve("runtime/test-version").exists())

            val executable = installer.install(workspace.resolve("runtime"))

            assertTrue(Files.isRegularFile(executable))
            assertEquals(2, attempts)
        }
    }

    private fun installer(
        archive: Path,
        download: (Path, Path) -> Unit,
    ): OllamaDistributionInstaller = OllamaDistributionInstaller(
        manifest = manifest(archive, sha256(archive)),
        downloader = AssetDownloader { source, target -> download(Path.of(source), target) },
        isSupportedPlatform = { true },
    )

    private fun manifest(archive: Path, checksum: String) = DistributionManifest(
        productName = "ollama",
        version = "test-version",
        assetUri = archive.toUri(),
        assetSha256 = checksum,
        assetSizeBytes = Files.size(archive),
        entryPoint = Path.of("ollama.exe"),
    )

    private fun createArchive(path: Path): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("ollama.exe"))
            zip.write("fake executable".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/ollama/library.dll"))
            zip.write("fake library".encodeToByteArray())
            zip.closeEntry()
        }
        return path
    }

    private fun sha256(path: Path): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),
    )

    private inline fun withTempDirectory(prefix: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
