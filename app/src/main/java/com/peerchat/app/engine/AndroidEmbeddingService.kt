package com.peerchat.app.engine

import android.content.Context
import com.peerchat.app.util.Logger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Android native embedding service using TensorFlow Lite as fallback.
 * Provides embeddings when the llama.cpp model doesn't support embeddings or is not loaded.
 * 
 * Note: This is a fallback service. Primary embeddings should come from llama.cpp via EngineNative.embed()
 */
class AndroidEmbeddingService(private val context: Context) {
    
    private var currentInterpreter: Interpreter? = null
    private var modelBuffer: ByteBuffer? = null
    private var currentConfig: EmbeddingConfig? = null
    private val isClosed = AtomicBoolean(false)
    
    /**
     * Configuration for embedding model
     */
    data class EmbeddingConfig(
        val modelPath: String,  // Path to TFLite model in assets
        val inputSize: Int,     // Maximum input sequence length
        val outputSize: Int     // Embedding dimension
    )
    
    companion object {
        // Universal Sentence Encoder Lite configuration (384 dimensions)
        // If using a different model, update these values
        private const val DEFAULT_MODEL_PATH = "models/universal_sentence_encoder_lite.tflite"
        private const val DEFAULT_INPUT_SIZE = 256
        private const val DEFAULT_OUTPUT_SIZE = 384
        
        // Predefined configurations for common embedding models
        val DEFAULT_CONFIG = EmbeddingConfig(
            modelPath = DEFAULT_MODEL_PATH,
            inputSize = DEFAULT_INPUT_SIZE,
            outputSize = DEFAULT_OUTPUT_SIZE
        )
    }
    
    /**
     * Generate embeddings for multiple texts using TensorFlow Lite
     * Returns empty arrays if the service is closed or model is not available
     */
    suspend fun generateEmbeddings(texts: Array<String>): Array<FloatArray> = withContext(Dispatchers.IO) {
        if (isClosed.get() || texts.isEmpty()) {
            return@withContext Array(texts.size) { FloatArray(0) }
        }
        
        // Ensure model is loaded
        val config = currentConfig ?: DEFAULT_CONFIG
        if (currentInterpreter == null) {
            loadModel(config)
        }
        
        val interpreter = currentInterpreter ?: return@withContext Array(texts.size) { FloatArray(0) }
        
        // Process texts in batches for better performance
        val results = mutableListOf<FloatArray>()
        
        for (text in texts) {
            try {
                val embedding = processText(interpreter, config, text)
                results.add(embedding)
            } catch (e: Exception) {
                Logger.w("AndroidEmbeddingService: failed to generate embedding", mapOf(
                    "textLength" to text.length,
                    "error" to e.message
                ), e)
                results.add(FloatArray(0))
            }
        }
        
        results.toTypedArray()
    }
    
    /**
     * Process a single text through the TensorFlow Lite model
     * Note: This is a generic implementation. Actual model requirements may vary.
     * Universal Sentence Encoder typically expects string input.
     */
    private fun processText(
        interpreter: Interpreter,
        config: EmbeddingConfig,
        text: String
    ): FloatArray {
        try {
            // Get input/output tensor info
            val inputTensor = interpreter.getInputTensor(0)
            val outputTensor = interpreter.getOutputTensor(0)
            
            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()
            
            // Determine output size from tensor shape
            val outputSize = if (outputShape.isNotEmpty()) {
                outputShape.fold(1) { acc, dim -> acc * dim }
            } else {
                config.outputSize
            }
            
            // Prepare output buffer
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
                .order(ByteOrder.nativeOrder())
            
            // Prepare input based on tensor type
            when (inputTensor.dataType()) {
                DataType.STRING -> {
                    // String input (Universal Sentence Encoder style)
                    // TensorFlow Lite string inputs expect ByteArray arrays
                    val inputArray = arrayOf(text.toByteArray())
                    val outputMap = mutableMapOf<Int, Any>()
                    outputMap[0] = outputBuffer
                    interpreter.runForMultipleInputsOutputs(inputArray, outputMap)
                }
                else -> {
                    // Numeric input - simple tokenization fallback
                    val tokens = tokenizeSimple(text, config.inputSize)
                    val inputBuffer = ByteBuffer.allocateDirect(tokens.size * 4)
                        .order(ByteOrder.nativeOrder())
                    for (token in tokens) {
                        inputBuffer.putFloat(token.toFloat())
                    }
                    inputBuffer.rewind()
                    
                    val outputMap = mutableMapOf<Int, Any>()
                    outputMap[0] = outputBuffer
                    interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
                }
            }
            
            // Extract and normalize embedding
            val embedding = FloatArray(outputSize)
            outputBuffer.rewind()
            for (i in 0 until outputSize) {
                embedding[i] = outputBuffer.float
            }
            
            // L2 normalization for cosine similarity
            normalize(embedding)
            
            return embedding
        } catch (e: Exception) {
            Logger.w("AndroidEmbeddingService: inference failed", mapOf(
                "textLength" to text.length,
                "error" to e.message
            ), e)
            // Return empty array - RAG service will filter this out
            return FloatArray(0)
        }
    }
    
    /**
     * Simple tokenization: convert text to integer tokens
     * This is a basic implementation - a production system would use a proper tokenizer
     */
    private fun tokenizeSimple(text: String, maxLength: Int): IntArray {
        // Simple character-based tokenization (ASCII values)
        // In production, this should use the model's actual tokenizer
        val tokens = text.take(maxLength)
            .map { it.code.coerceIn(0, 255) }
            .toIntArray()
        
        // Pad to maxLength
        return if (tokens.size < maxLength) {
            tokens + IntArray(maxLength - tokens.size) { 0 }
        } else {
            tokens
        }
    }
    
    /**
     * L2 normalize embedding vector
     */
    private fun normalize(embedding: FloatArray) {
        var sum = 0f
        for (x in embedding) {
            sum += x * x
        }
        val norm = sqrt(sum)
        if (norm > 0f) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
    }
    
    /**
     * Load TensorFlow Lite model
     */
    private suspend fun loadModel(config: EmbeddingConfig) {
        if (isClosed.get()) {
            Logger.w("AndroidEmbeddingService: service is closed, cannot load model")
            return
        }
        
        try {
            // Clean up previous model and buffer
            currentInterpreter?.close()
            currentInterpreter = null
            
            // Clear previous model buffer reference to help GC
            modelBuffer?.clear()
            modelBuffer = null
            
            // Try to load model from assets
            val assetManager = context.assets
            val buffer = try {
                assetManager.openFd(config.modelPath).use { fd ->
                    val channel = FileInputStream(fd.fileDescriptor).channel
                    channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            } catch (e: Exception) {
                Logger.w("AndroidEmbeddingService: model not found in assets: ${config.modelPath}. " +
                    "Android native embeddings will not be available. " +
                    "RAG requires a model with embedding support or a TensorFlow Lite embedding model in assets.")
                return
            }
            
            // Store buffer reference for cleanup
            modelBuffer = buffer
            
            // Create interpreter options for optimization
            val options = Interpreter.Options().apply {
                setNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                setUseNNAPI(false) // NNAPI can be unreliable, disable for now
                setUseXNNPACK(true) // Use XNNPACK for better performance
            }
            
            currentInterpreter = Interpreter(buffer, options)
            currentConfig = config
            
            Logger.i("AndroidEmbeddingService: loaded model", mapOf(
                "model" to config.modelPath,
                "inputSize" to config.inputSize,
                "outputSize" to config.outputSize
            ))
            
        } catch (e: Exception) {
            Logger.w("AndroidEmbeddingService: failed to load model", mapOf(
                "model" to config.modelPath,
                "error" to e.message
            ), e)
            currentInterpreter = null
            modelBuffer = null
        }
    }
    
    /**
     * Close the service and release resources
     */
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            currentInterpreter?.close()
            currentInterpreter = null
            modelBuffer?.clear()
            modelBuffer = null
            currentConfig = null
            Logger.i("AndroidEmbeddingService: closed")
        }
    }
}

