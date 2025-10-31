package com.peerchat.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peerchat.data.PeerChatRepository
import com.peerchat.data.db.*
import com.peerchat.rag.RagService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class PerformanceBenchmarkTest {

    private lateinit var database: PeerDatabase
    private lateinit var repository: PeerChatRepository
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PeerChatRepository(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `database insertion performance benchmark`() = runBlocking {
        val chatId = repository.createChat(null, "Benchmark Chat", "System", "model")

        val messageCount = 1000
        val messages = List(messageCount) { index ->
            Message(
                chatId = chatId,
                role = if (index % 2 == 0) "user" else "assistant",
                contentMarkdown = "Benchmark message $index with some content",
                tokens = 8,
                ttfsMs = 100L,
                tps = 80f,
                contextUsedPct = 0.1f,
                createdAt = System.currentTimeMillis() + index,
                metaJson = "{}"
            )
        }

        // Measure individual insertions
        val individualStart = System.currentTimeMillis()
        messages.forEach { repository.insertMessage(it) }
        val individualTime = System.currentTimeMillis() - individualStart

        // Measure bulk retrieval
        val retrieveStart = System.currentTimeMillis()
        val retrievedMessages = repository.listMessages(chatId)
        val retrieveTime = System.currentTimeMillis() - retrieveStart

        // Performance assertions
        assertEquals("All messages should be inserted", messageCount, retrievedMessages.size)
        assertTrue("Individual insertions should be fast enough", individualTime < 10000) // 10 seconds max
        assertTrue("Bulk retrieval should be fast", retrieveTime < 2000) // 2 seconds max

        val messagesPerSecond = (messageCount * 1000.0) / individualTime
        println("Insertion performance: $messagesPerSecond messages/second")

        val retrievalMsPerMessage = retrieveTime.toDouble() / messageCount
        println("Retrieval performance: ${retrievalMsPerMessage}ms per message")
    }

    @Test
    fun `RAG indexing performance benchmark`() = runBlocking {
        // Create a larger document for benchmarking
        val documentContent = """
            Artificial Intelligence (AI) is a field of computer science that aims to create machines capable of intelligent behavior.
            Machine Learning is a subset of AI that focuses on algorithms that can learn from data without being explicitly programmed.
            Deep Learning uses neural networks with multiple layers to model complex patterns in data.
            Natural Language Processing (NLP) enables computers to understand and generate human language.
            Computer Vision allows machines to interpret and understand visual information from the world.
            Reinforcement Learning is a type of machine learning where agents learn by interacting with their environment.
            """.trimIndent().repeat(10) // Make it larger

        val document = Document(
            uri = "content://benchmark/document/1",
            title = "AI Benchmark Document",
            hash = "b665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            mime = "text/plain",
            textBytes = documentContent.toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val docId = repository.upsertDocument(document)

        // Benchmark chunk creation (simulating RAG indexing)
        val chunkStart = System.currentTimeMillis()

        // Create chunks manually for benchmark
        val chunkSize = 200
        val chunks = documentContent.chunked(chunkSize).mapIndexed { index, text ->
            RagChunk(
                docId = docId,
                start = index * chunkSize,
                end = minOf((index + 1) * chunkSize, documentContent.length),
                text = text,
                tokenCount = (text.length / 4).coerceAtLeast(1), // Rough token estimation
                embeddingId = null
            )
        }

        val chunkIds = chunks.map { repository.insertRagChunk(it) }
        val chunkTime = System.currentTimeMillis() - chunkStart

        // Benchmark search performance
        val searchStart = System.currentTimeMillis()
        val searchResults = repository.searchChunks("machine learning", limit = 50)
        val searchTime = System.currentTimeMillis() - searchStart

        // Performance assertions
        assertTrue("Should create chunks reasonably fast", chunkTime < 5000) // 5 seconds max
        assertTrue("Should find search results", searchResults.isNotEmpty())
        assertTrue("Search should be fast", searchTime < 500) // 500ms max

        println("Chunk creation: ${chunks.size} chunks in ${chunkTime}ms")
        println("Search performance: ${searchResults.size} results in ${searchTime}ms")

        // Verify chunk integrity
        val allChunks = repository.getChunksByDocument(docId)
        assertEquals("All chunks should be created", chunks.size, allChunks.size)
    }

    @Test
    fun `concurrent operations performance test`() = runBlocking {
        val chatIds = List(5) { index ->
            repository.createChat(null, "Concurrent Chat $index", "System", "model")
        }

        val totalMessages = 500
        val messagesPerChat = totalMessages / chatIds.size

        val concurrentStart = System.currentTimeMillis()

        // Launch concurrent operations
        val jobs = chatIds.mapIndexed { chatIndex, chatId ->
            kotlinx.coroutines.async(coroutineContext) {
                val messages = List(messagesPerChat) { msgIndex ->
                    Message(
                        chatId = chatId,
                        role = if (msgIndex % 2 == 0) "user" else "assistant",
                        contentMarkdown = "Concurrent message ${chatIndex * messagesPerChat + msgIndex}",
                        tokens = 5,
                        ttfsMs = 50L,
                        tps = 100f,
                        contextUsedPct = 0.05f,
                        createdAt = System.currentTimeMillis() + (chatIndex * messagesPerChat + msgIndex),
                        metaJson = "{}"
                    )
                }
                messages.forEach { repository.insertMessage(it) }
            }
        }

        jobs.forEach { it.await() }
        val concurrentTime = System.currentTimeMillis() - concurrentStart

        // Verify all messages were inserted
        val totalInserted = chatIds.sumOf { repository.listMessages(it).size }
        assertEquals("All messages should be inserted", totalMessages, totalInserted)

        assertTrue("Concurrent operations should complete reasonably fast", concurrentTime < 15000) // 15 seconds max

        val operationsPerSecond = (totalMessages * 1000.0) / concurrentTime
        println("Concurrent performance: $operationsPerSecond operations/second")
    }

    @Test
    fun `memory efficiency test with large dataset`() = runBlocking {
        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        val chatId = repository.createChat(null, "Memory Test Chat", "System", "model")

        // Create a large number of messages
        val largeMessageCount = 5000
        val messages = List(largeMessageCount) { index ->
            Message(
                chatId = chatId,
                role = "user",
                contentMarkdown = "Memory test message $index with some additional content to increase size",
                tokens = 12,
                ttfsMs = 200L,
                tps = 60f,
                contextUsedPct = 0.15f,
                createdAt = System.currentTimeMillis() + index,
                metaJson = "{\"metadata\": \"test data for memory benchmarking\"}"
            )
        }

        val insertStart = System.currentTimeMillis()
        messages.forEach { repository.insertMessage(it) }
        val insertTime = System.currentTimeMillis() - insertStart

        val afterInsertMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryDelta = afterInsertMemory - initialMemory

        // Test retrieval of subset
        val retrieveStart = System.currentTimeMillis()
        val recentMessages = repository.listMessages(chatId).take(100)
        val retrieveTime = System.currentTimeMillis() - retrieveStart

        // Cleanup
        repository.deleteChat(chatId)

        // Performance assertions
        assertEquals("Should retrieve correct subset", 100, recentMessages.size)
        assertTrue("Large insertions should complete", insertTime < 30000) // 30 seconds max
        assertTrue("Subset retrieval should be fast", retrieveTime < 1000) // 1 second max

        // Memory should not grow excessively (allow some growth for database operations)
        val maxExpectedGrowth = 50 * 1024 * 1024L // 50MB max growth
        assertTrue("Memory growth should be reasonable", memoryDelta < maxExpectedGrowth)

        println("Memory test: ${largeMessageCount} messages inserted in ${insertTime}ms")
        println("Memory usage: ${memoryDelta / 1024 / 1024}MB increase")
        println("Retrieval performance: 100 messages in ${retrieveTime}ms")
    }

    @Test
    fun `index effectiveness benchmark`() = runBlocking {
        // Create multiple chats with messages
        val chatIds = List(10) { index ->
            repository.createChat(null, "Index Test Chat $index", "System", "model")
        }

        // Insert messages with searchable content
        val searchableTerms = listOf("artificial", "intelligence", "machine", "learning", "deep", "neural", "network")
        val totalMessages = 1000

        val insertStart = System.currentTimeMillis()
        chatIds.forEachIndexed { chatIndex: Int, chatId: Long ->
            val messagesPerChat = totalMessages / chatIds.size
            val messages = List(messagesPerChat) { msgIndex: Int ->
                val term = searchableTerms[(chatIndex + msgIndex) % searchableTerms.size]
                Message(
                    chatId = chatId,
                    role = "user",
                    contentMarkdown = "This message contains $term and some other content",
                    tokens = 8,
                    ttfsMs = 100L,
                    tps = 80f,
                    contextUsedPct = 0.1f,
                    createdAt = System.currentTimeMillis() + chatIndex * messagesPerChat + msgIndex,
                    metaJson = "{}"
                )
            }
            messages.forEach { repository.insertMessage(it) }
        }
        val insertTime = System.currentTimeMillis() - insertStart

        // Test search performance
        val searchStart = System.currentTimeMillis()
        val searchResults = repository.searchMessages("artificial intelligence", limit = 50)
        val searchTime = System.currentTimeMillis() - searchStart

        // Test filtering by chat
        val firstChatId = chatIds.first()
        val chatSpecificStart = System.currentTimeMillis()
        val chatResults = repository.searchMessagesInChat(firstChatId, "machine learning", limit = 20)
        val chatSpecificTime = System.currentTimeMillis() - chatSpecificStart

        // Performance assertions
        assertTrue("Should find search results", searchResults.isNotEmpty())
        assertTrue("Should find chat-specific results", chatResults.isNotEmpty())
        assertTrue("Global search should be reasonably fast", searchTime < 2000) // 2 seconds max
        assertTrue("Chat-specific search should be fast", chatSpecificTime < 500) // 500ms max

        println("Index benchmark: $totalMessages messages inserted in ${insertTime}ms")
        println("Global search: ${searchResults.size} results in ${searchTime}ms")
        println("Chat search: ${chatResults.size} results in ${chatSpecificTime}ms")
    }
}
