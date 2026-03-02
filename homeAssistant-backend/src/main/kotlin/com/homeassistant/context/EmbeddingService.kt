package com.homeassistant.context

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.ndarray.NDManager
import ai.djl.repository.zoo.Criteria
import ai.djl.training.util.ProgressBar
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection

private val log = LoggerFactory.getLogger(EmbeddingService::class.java)

data class SimilarResult(val rowid: Long, val distance: Float)

/**
 * Embedding service using DJL + multilingual-e5-small (same model as TypeScript side).
 *
 * For vector similarity search, the vec0 virtual tables in SQLite require the
 * sqlite-vec extension to be loaded. This service uses raw JDBC to query them.
 *
 * If the extension isn't available, findSimilar returns an empty list and
 * ContextRetriever falls back to recent retrieval.
 */
class EmbeddingService {

    companion object {
        const val EMBEDDING_DIM = 384
        val ALLOWED_VEC_TABLES = setOf("vec_memos", "vec_todos", "vec_recipes")
    }

    // Lazily initialised — DJL model is loaded on first use
    private val model by lazy {
        try {
            val criteria = Criteria.builder()
                .setTypes(ai.djl.ndarray.NDList::class.java, ai.djl.ndarray.NDList::class.java)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/multilingual-e5-small")
                .optProgress(ProgressBar())
                .build()
            criteria.loadModel()
        } catch (e: Exception) {
            log.warn("Failed to load embedding model: ${e.message}. Vector similarity disabled.")
            null
        }
    }

    /**
     * Generate a normalized mean-pool embedding for [text].
     * Returns null if the model isn't available.
     */
    fun embed(text: String): FloatArray? {
        val predictor = model?.newPredictor() ?: return null
        return try {
            NDManager.newBaseManager().use { manager ->
                // Simple mean-pool over token embeddings
                val tokenizer = HuggingFaceTokenizer.newInstance("intfloat/multilingual-e5-small")
                val encoding = tokenizer.encode(text)
                val inputIds = manager.create(encoding.ids)
                val attentionMask = manager.create(encoding.attentionMask)
                val input = ai.djl.ndarray.NDList(inputIds.expandDims(0), attentionMask.expandDims(0))
                val output = predictor.predict(input)
                val embeddingNDArray = output.first()
                    .mean(intArrayOf(1))   // mean pool over sequence dimension
                    .normalize(2.0, 1)     // L2 normalize
                embeddingNDArray.toFloatArray()
            }
        } catch (e: Exception) {
            log.warn("Embedding failed: ${e.message}")
            null
        } finally {
            predictor.close()
        }
    }

    /**
     * Store an embedding in the vec0 virtual table via raw JDBC.
     * [conn] must have the sqlite-vec extension loaded.
     */
    fun store(conn: Connection, vecTable: String, rowId: Long, embedding: FloatArray) {
        require(vecTable in ALLOWED_VEC_TABLES) { "Disallowed vec table: $vecTable" }
        val bytes = floatArrayToBytes(embedding)
        conn.prepareStatement("DELETE FROM $vecTable WHERE rowid = ?")
            .use { it.setLong(1, rowId); it.executeUpdate() }
        conn.prepareStatement("INSERT INTO $vecTable(rowid, embedding) VALUES (?, ?)")
            .use { it.setLong(1, rowId); it.setBytes(2, bytes); it.executeUpdate() }
    }

    /**
     * Find the [limit] most similar rows in [vecTable].
     * Returns empty list if vec0 extension isn't loaded or query fails.
     */
    fun findSimilar(conn: Connection, vecTable: String, queryEmbedding: FloatArray, limit: Int): List<SimilarResult> {
        require(vecTable in ALLOWED_VEC_TABLES) { "Disallowed vec table: $vecTable" }
        return try {
            val bytes = floatArrayToBytes(queryEmbedding)
            val results = mutableListOf<SimilarResult>()
            conn.prepareStatement(
                "SELECT rowid, distance FROM $vecTable WHERE embedding MATCH ? ORDER BY distance LIMIT ?"
            ).use { stmt ->
                stmt.setBytes(1, bytes)
                stmt.setInt(2, limit)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    results.add(SimilarResult(rs.getLong(1), rs.getFloat(2)))
                }
            }
            results
        } catch (e: Exception) {
            log.debug("vec0 query failed (extension may not be loaded): ${e.message}")
            emptyList()
        }
    }

    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }
}
