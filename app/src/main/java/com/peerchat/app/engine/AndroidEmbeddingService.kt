package com.peerchat.app.engine

import android.content.Context
import com.peerchat.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.model.Model
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Android native embedding service providing TensorFlow Lite-based text embeddings
 * as fallback when llama.cpp models don't support embeddings.
 */
class AndroidEmbeddingService(private val context: Context) {

    data class EmbeddingResult(
        val embeddings: Array<FloatArray>,
        val dimension: Int,
        val modelUsed: String
    )

    data class EmbeddingConfig(
        val modelPath: String,
        val inputSize: Int,
        val outputSize: Int,
        val vocabulary: Map<String, Int>,
        val tokenizer: TextTokenizer
    )

    sealed class TextTokenizer {
        abstract fun tokenize(text: String): IntArray
        abstract fun encode(text: String, maxLength: Int = 512): IntArray

        class BasicTokenizer(
            private val vocab: Map<String, Int>,
            private val unknownTokenId: Int = 0,
            private val maxLength: Int = 512
        ) : TextTokenizer() {

            override fun tokenize(text: String): IntArray {
                return text.lowercase()
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { token -> vocab[token] ?: unknownTokenId }
                    .toIntArray()
            }

            override fun encode(text: String, maxLength: Int): IntArray {
                val tokens = tokenize(text)
                return if (tokens.size <= maxLength) {
                    tokens
                } else {
                    tokens.copyOfRange(0, maxLength)
                }
            }
        }
    }

    // Universal Sentence Encoder configuration (lightweight model)
    private val useConfig = EmbeddingConfig(
        modelPath = "universal_sentence_encoder.tflite",
        inputSize = 512,
        outputSize = 512,
        vocabulary = createBasicVocabulary(),
        tokenizer = TextTokenizer.BasicTokenizer(createBasicVocabulary())
    )

    // MiniLM configuration (compact transformer-based embeddings)
    private val minilmConfig = EmbeddingConfig(
        modelPath = "minilm_embeddings.tflite",
        inputSize = 256,
        outputSize = 384,
        vocabulary = createBasicVocabulary(),
        tokenizer = TextTokenizer.BasicTokenizer(createBasicVocabulary())
    )

    private var currentInterpreter: Interpreter? = null
    private var currentConfig: EmbeddingConfig? = null

    /**
     * Generate embeddings for multiple texts using Android native models
     */
    suspend fun generateEmbeddings(texts: Array<String>): EmbeddingResult = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) {
            return@withContext EmbeddingResult(emptyArray(), 0, "none")
        }

        try {
            // Try MiniLM first (better quality), fall back to USE
            val result = tryGenerateWithConfig(minilmConfig, texts)
            if (result != null) {
                return@withContext result
            }

            val fallbackResult = tryGenerateWithConfig(useConfig, texts)
            if (fallbackResult != null) {
                return@withContext fallbackResult
            }

            // Last resort: basic bag-of-words embeddings
            generateBasicEmbeddings(texts)

        } catch (e: Exception) {
            Logger.w("AndroidEmbeddingService: embedding generation failed", mapOf("error" to e.message), e)
            EmbeddingResult(Array(texts.size) { FloatArray(0) }, 0, "error")
        }
    }

    /**
     * Generate embeddings using a specific model configuration
     */
    private suspend fun tryGenerateWithConfig(config: EmbeddingConfig, texts: Array<String>): EmbeddingResult? {
        return try {
            // Load model if not already loaded
            if (currentConfig?.modelPath != config.modelPath) {
                loadModel(config)
            }

            val interpreter = currentInterpreter ?: return null

            // Process texts in batches to avoid memory issues
            val batchSize = minOf(texts.size, 8) // Process up to 8 texts at once
            val allEmbeddings = mutableListOf<FloatArray>()

            for (i in texts.indices step batchSize) {
                val batchEnd = minOf(i + batchSize, texts.size)
                val batchTexts = texts.copyOfRange(i, batchEnd)
                val batchEmbeddings = processBatch(interpreter, config, batchTexts)
                allEmbeddings.addAll(batchEmbeddings)
            }

            EmbeddingResult(
                embeddings = allEmbeddings.toTypedArray(),
                dimension = config.outputSize,
                modelUsed = config.modelPath
            )

        } catch (e: Exception) {
            Logger.w("AndroidEmbeddingService: failed to generate with ${config.modelPath}",
                mapOf("error" to e.message), e)
            null
        }
    }

    /**
     * Process a batch of texts through the TensorFlow Lite model
     */
    private fun processBatch(
        interpreter: Interpreter,
        config: EmbeddingConfig,
        texts: Array<String>
    ): List<FloatArray> {

        val embeddings = mutableListOf<FloatArray>()

        for (text in texts) {
            try {
                // Tokenize text
                val tokens = config.tokenizer.encode(text, config.inputSize)

                // Create input buffer
                val inputBuffer = createInputBuffer(tokens, config.inputSize)

                // Prepare output buffer
                val outputBuffer = ByteBuffer.allocateDirect(config.outputSize * 4)
                    .order(ByteOrder.nativeOrder())

                // Run inference
                interpreter.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()

                // Extract embedding
                val embedding = FloatArray(config.outputSize)
                for (i in 0 until config.outputSize) {
                    embedding[i] = outputBuffer.getFloat()
                }

                // L2 normalize
                normalizeEmbedding(embedding)

                embeddings.add(embedding)

            } catch (e: Exception) {
                Logger.w("AndroidEmbeddingService: failed to process text", mapOf(
                    "textLength" to text.length,
                    "error" to e.message
                ), e)
                // Add zero embedding as fallback
                embeddings.add(FloatArray(config.outputSize))
            }
        }

        return embeddings
    }

    /**
     * Create input buffer for TensorFlow Lite model
     */
    private fun createInputBuffer(tokens: IntArray, maxLength: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(maxLength * 4)
            .order(ByteOrder.nativeOrder())

        for (i in 0 until minOf(tokens.size, maxLength)) {
            buffer.putInt(tokens[i])
        }

        // Pad with zeros if needed
        while (buffer.position() < buffer.capacity()) {
            buffer.putInt(0)
        }

        buffer.rewind()
        return buffer
    }

    /**
     * L2 normalize embedding vector
     */
    private fun normalizeEmbedding(embedding: FloatArray) {
        var norm = 0.0f
        for (value in embedding) {
            norm += value * value
        }
        norm = sqrt(norm)

        if (norm > 0.0f) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
    }

    /**
     * Load TensorFlow Lite model
     */
    private suspend fun loadModel(config: EmbeddingConfig) {
        try {
            // Clean up previous model
            currentInterpreter?.close()
            currentInterpreter = null

            // Try to load model from assets
            val assetManager = context.assets
            val modelBuffer = try {
                assetManager.openFd(config.modelPath).use { fd ->
                    val channel = FileInputStream(fd.fileDescriptor).channel
                    channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            } catch (e: Exception) {
                Logger.w("AndroidEmbeddingService: model not found in assets: ${config.modelPath}")
                return
            }

            // Create interpreter options for optimization
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                setUseNNAPI(false) // NNAPI can be unreliable, disable for now
                setUseXNNPACK(true) // Use XNNPACK for better performance
            }

            currentInterpreter = Interpreter(modelBuffer, options)
            currentConfig = config

            Logger.i("AndroidEmbeddingService: loaded model", mapOf(
                "model" to config.modelPath,
                "inputSize" to config.inputSize,
                "outputSize" to config.outputSize
            ))

        } catch (e: Exception) {
            Logger.w("AndroidEmbeddingService: failed to load model ${config.modelPath}",
                mapOf("error" to e.message), e)
        }
    }

    /**
     * Generate basic bag-of-words style embeddings as last resort
     */
    private fun generateBasicEmbeddings(texts: Array<String>): EmbeddingResult {
        val dimension = 300 // Basic embedding dimension
        val embeddings = Array(texts.size) { FloatArray(dimension) }

        for (i in texts.indices) {
            val text = texts[i]
            val words = text.lowercase().split(Regex("\\s+")).filter { it.length > 2 }

            // Simple hash-based embedding
            for (word in words) {
                val hash = word.hashCode()
                val index = (hash % dimension).toInt()
                if (index >= 0) {
                    embeddings[i][index] += 1.0f
                }
            }

            // L2 normalize
            normalizeEmbedding(embeddings[i])
        }

        return EmbeddingResult(embeddings, dimension, "basic_bow")
    }

    /**
     * Create basic English vocabulary for tokenization
     */
    private fun createBasicVocabulary(): Map<String, Int> {
        // Load from assets or create minimal vocabulary
        // For now, create a basic vocabulary of common English words
        val commonWords = listOf(
            "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
            "an", "a", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had",
            "do", "does", "did", "will", "would", "could", "should", "can", "may", "might",
            "this", "that", "these", "those", "i", "you", "he", "she", "it", "we", "they",
            "me", "him", "her", "us", "them", "my", "your", "his", "its", "our", "their",
            "what", "when", "where", "why", "how", "which", "who", "all", "any", "both",
            "each", "few", "more", "most", "other", "some", "such", "no", "nor", "not",
            "only", "own", "same", "so", "than", "too", "very", "just", "now", "here", "there"
        )

        return commonWords.withIndex().associate { (index, word) -> word to index + 1 }
    }

    /**
     * Clean up resources
     */
    fun close() {
        currentInterpreter?.close()
        currentInterpreter = null
        currentConfig = null
    }

    companion object {
        private const val TAG = "AndroidEmbeddingService"
    }
}
