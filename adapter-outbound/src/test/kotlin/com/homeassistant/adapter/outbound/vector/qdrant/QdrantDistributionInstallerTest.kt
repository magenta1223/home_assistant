package com.homeassistant.adapter.outbound.vector.qdrant

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

class QdrantDistributionInstallerTest {
    @Test
    fun `installs verified archive and reuses complete installation`() {
        withTempDirectory("qdrant-installer") { workspace ->
            val archive = createArchive(workspace.resolve("qdrant.zip"))
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
        withTempDirectory("qdrant-checksum") { workspace ->
            val archive = createArchive(workspace.resolve("qdrant.zip"))
            val installer = QdrantDistributionInstaller(
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
        withTempDirectory("qdrant-retry") { workspace ->
            val archive = createArchive(workspace.resolve("qdrant.zip"))
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
    ): QdrantDistributionInstaller = QdrantDistributionInstaller(
        manifest = manifest(archive, sha256(archive)),
        downloader = AssetDownloader { source, target -> download(Path.of(source), target) },
        isSupportedPlatform = { true },
    )

    private fun manifest(archive: Path, checksum: String) = DistributionManifest(
        productName = "qdrant",
        version = "test-version",
        assetUri = archive.toUri(),
        assetSha256 = checksum,
        assetSizeBytes = Files.size(archive),
        entryPoint = Path.of("qdrant.exe"),
    )

    private fun createArchive(path: Path): Path {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            zip.putNextEntry(ZipEntry("qdrant.exe"))
            zip.write("fake executable".encodeToByteArray())
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
