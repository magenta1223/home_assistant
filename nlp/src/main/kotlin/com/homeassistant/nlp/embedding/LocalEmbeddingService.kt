package com.homeassistant.nlp.embedding

import ai.djl.MalformedModelException
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.inference.Predictor
import ai.djl.ndarray.NDArray
import ai.djl.ndarray.NDList
import ai.djl.repository.zoo.Criteria
import ai.djl.repository.zoo.ModelNotFoundException
import ai.djl.repository.zoo.ZooModel
import ai.djl.translate.NoBatchifyTranslator
import ai.djl.translate.TranslatorContext
import com.homeassistant.core.constants.AppConfig
import com.homeassistant.domain.memory.EmbeddingService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

class LocalEmbeddingService internal constructor(
    private val predictor: LocalTextEmbeddingPredictor,
    private val vectorSize: Int = AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE,
) : EmbeddingService, AutoCloseable {
    override fun embed(text: String): List<Float> {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "Embedding text must not be blank" }

        val vector = normalize(predictor.predict(normalizedText))
        check(vector.size == vectorSize) {
            "Embedding vector size mismatch: expected=$vectorSize actual=${vector.size}"
        }
        return vector.toList()
    }

    override fun close() {
        predictor.close()
    }

    companion object {
        fun fromModelPath(
            modelPath: Path,
            vectorSize: Int = AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE,
        ): LocalEmbeddingService {
            require(Files.isDirectory(modelPath)) { "EMBEDDING_MODEL_PATH must be a directory: $modelPath" }
            val tokenizerPath = modelPath.resolve("tokenizer.json")
            require(Files.isRegularFile(tokenizerPath)) {
                "EMBEDDING_MODEL_PATH must contain tokenizer.json: $tokenizerPath"
            }
            return LocalEmbeddingService(
                DjlLocalTextEmbeddingPredictor(modelPath = modelPath, tokenizerPath = tokenizerPath),
                vectorSize = vectorSize,
            )
        }
    }
}

internal interface LocalTextEmbeddingPredictor : AutoCloseable {
    fun predict(text: String): FloatArray
}

internal class DjlLocalTextEmbeddingPredictor(
    modelPath: Path,
    tokenizerPath: Path,
) : LocalTextEmbeddingPredictor {
    private val tokenizer: HuggingFaceTokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath)
    private val model: ZooModel<String, FloatArray>
    private val predictor: Predictor<String, FloatArray>

    init {
        model = loadModel(modelPath, tokenizer)
        predictor = model.newPredictor()
    }

    override fun predict(text: String): FloatArray = predictor.predict(text)

    override fun close() {
        predictor.close()
        model.close()
        tokenizer.close()
    }

    private fun loadModel(
        modelPath: Path,
        tokenizer: HuggingFaceTokenizer,
    ): ZooModel<String, FloatArray> =
        try {
            Criteria.builder()
                .setTypes(String::class.java, FloatArray::class.java)
                .optModelPath(modelPath)
                .optEngine("PyTorch")
                .optTranslator(SentenceEmbeddingTranslator(tokenizer))
                .build()
                .loadModel()
        } catch (error: IOException) {
            throw IllegalStateException("Failed to load local embedding model from $modelPath", error)
        } catch (error: ModelNotFoundException) {
            throw IllegalStateException("Failed to load local embedding model from $modelPath", error)
        } catch (error: MalformedModelException) {
            throw IllegalStateException("Failed to load local embedding model from $modelPath", error)
        }
}

private class SentenceEmbeddingTranslator(
    private val tokenizer: HuggingFaceTokenizer,
) : NoBatchifyTranslator<String, FloatArray> {
    override fun processInput(ctx: TranslatorContext, input: String): NDList {
        val encoding = tokenizer.encode(input)
        ctx.setAttachment(ATTENTION_MASK, encoding.attentionMask)
        return encoding.toNDList(ctx.ndManager, true)
    }

    override fun processOutput(ctx: TranslatorContext, list: NDList): FloatArray {
        val attentionMask = ctx.getAttachment(ATTENTION_MASK) as? LongArray
            ?: error("Missing attention mask for embedding output")
        return poolSentenceEmbedding(list.head(), attentionMask)
    }

    private companion object {
        const val ATTENTION_MASK = "attentionMask"
    }
}

private fun poolSentenceEmbedding(hiddenState: NDArray, attentionMask: LongArray): FloatArray {
    val dims = hiddenState.shape.shape
    val values = hiddenState.toFloatArray()

    if (dims.size == 2 && dims[0] == 1L) {
        return normalize(values)
    }
    require(dims.size == 2 || dims.size == 3) {
        "Unsupported embedding output shape: ${hiddenState.shape}"
    }

    val sequenceLength = dims[dims.size - 2].toInt()
    val hiddenSize = dims.last().toInt()
    val pooled = FloatArray(hiddenSize)
    var tokenCount = 0

    repeat(sequenceLength.coerceAtMost(attentionMask.size)) { tokenIndex ->
        if (attentionMask[tokenIndex] > 0) {
            val offset = tokenIndex * hiddenSize
            repeat(hiddenSize) { hiddenIndex ->
                pooled[hiddenIndex] += values[offset + hiddenIndex]
            }
            tokenCount += 1
        }
    }

    require(tokenCount > 0) { "Embedding output has no non-padding tokens" }
    repeat(hiddenSize) { index ->
        pooled[index] /= tokenCount.toFloat()
    }
    return normalize(pooled)
}

private fun normalize(vector: FloatArray): FloatArray {
    var normSquared = 0.0
    vector.forEach { value -> normSquared += value * value }
    val norm = sqrt(normSquared).toFloat()
    require(norm > 0f) { "Embedding vector norm must be positive" }
    return FloatArray(vector.size) { index -> vector[index] / norm }
}
