package com.homeassistant.adapter.outbound.reference

import com.homeassistant.codex.completion.CompletionClient
import com.homeassistant.codex.completion.CodexImage
import com.homeassistant.domain.source.SourceReferenceDraft
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import com.homeassistant.domain.source.InvalidSourceReferenceException

class CodexSourceReferenceInterpreterTest {
    @Test
    fun `image is normalized and interpreted as one evidence segment`() = runBlocking {
        val client = RecordingImageClient()
        val interpreter = CodexSourceReferenceInterpreter(client)

        val result = interpreter.interpret(
            SourceReferenceDraft("door.png", "image/png", pngImage()),
        )

        assertEquals(listOf("image"), result.map { it.segmentKey })
        assertEquals("해석-image", result.single().content)
        assertEquals(listOf(1), client.imageCounts)
        assertTrue(client.images.single().single().bytes.isNotEmpty())
    }

    @Test
    fun `PDF pages are interpreted in bounded ordered batches`() = runBlocking {
        val client = RecordingImageClient()
        val interpreter = CodexSourceReferenceInterpreter(client)

        val result = interpreter.interpret(
            SourceReferenceDraft("manual.pdf", "application/pdf", blankPdf(pageCount = 5)),
        )

        assertEquals((1..5).map { "page-$it" }, result.map { it.segmentKey })
        assertEquals(listOf(4, 1), client.imageCounts)
    }

    @Test
    fun `declared image type must match original bytes`() {
        val interpreter = CodexSourceReferenceInterpreter(RecordingImageClient())

        assertFailsWith<InvalidSourceReferenceException> {
            runBlocking {
                interpreter.interpret(SourceReferenceDraft("fake.png", "image/png", "not-an-image".toByteArray()))
            }
        }
    }

    private class RecordingImageClient : CompletionClient {
        val imageCounts = mutableListOf<Int>()
        val images = mutableListOf<List<CodexImage>>()

        override suspend fun complete(system: String, userMessage: String, outputSchema: String): String =
            error("image completion expected")

        override suspend fun completeWithImages(
            system: String,
            userMessage: String,
            outputSchema: String,
            images: List<CodexImage>,
        ): String {
            imageCounts += images.size
            this.images += images
            val keys = userMessage.substringAfter("Attached images correspond in order to: ")
                .substringBefore('\n')
                .split(", ")
            return keys.joinToString(
                prefix = "{\"interpretations\":[",
                postfix = "]}",
            ) { key -> "{\"segmentKey\":\"$key\",\"content\":\"해석-$key\"}" }
        }
    }

    private fun pngImage(): ByteArray = ByteArrayOutputStream().use { output ->
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(image, "png", output)
        output.toByteArray()
    }

    private fun blankPdf(pageCount: Int): ByteArray = ByteArrayOutputStream().use { output ->
        PDDocument().use { document ->
            repeat(pageCount) { document.addPage(PDPage()) }
            document.save(output)
        }
        output.toByteArray()
    }
}
