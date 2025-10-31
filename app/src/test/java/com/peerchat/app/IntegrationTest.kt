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
import java.io.File

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class IntegrationTest {

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
    fun `complete chat workflow integration test`() = runBlocking {
        // Create folder
        val folderId = repository.createFolder("Test Folder")

        // Create chat
        val chatId = repository.createChat(
            folderId = folderId,
            title = "Integration Test Chat",
            systemPrompt = "You are a helpful assistant",
            modelId = "llama-7b"
        )

        // Add messages
        val userMessageId = repository.insertMessage(
            Message(
                chatId = chatId,
                role = "user",
                contentMarkdown = "Hello, how are you?",
                tokens = 5,
                ttfsMs = 0,
                tps = 0f,
                contextUsedPct = 0f,
                createdAt = System.currentTimeMillis(),
                metaJson = "{}"
            )
        )

        val assistantMessageId = repository.insertMessage(
            Message(
                chatId = chatId,
                role = "assistant",
                contentMarkdown = "I'm doing well, thank you for asking!",
                tokens = 8,
                ttfsMs = 150,
                tps = 53.3f,
                contextUsedPct = 0.2f,
                createdAt = System.currentTimeMillis() + 1000,
                metaJson = "{\"reasoning\": \"Responding to greeting\"}"
            )
        )

        // Verify chat retrieval
        val chat = repository.getChat(chatId)
        assertNotNull("Chat should exist", chat)
        assertEquals("Title should match", "Integration Test Chat", chat?.title)
        assertEquals("Folder ID should match", folderId, chat?.folderId)

        // Verify message retrieval
        val messages = repository.listMessages(chatId)
        assertEquals("Should have 2 messages", 2, messages.size)
        assertEquals("First message should be user", "user", messages[0].role)
        assertEquals("Second message should be assistant", "assistant", messages[1].role)

        // Test chat statistics
        val chatStats = repository.getChatStats(chatId)
        assertNotNull("Should have chat stats", chatStats)
        assertEquals("Should have 2 messages", 2, chatStats?.messageCount)
        assertEquals("Should have 13 total tokens", 13, chatStats?.totalTokens)

        // Update chat
        repository.updateChatTitle(chatId, "Updated Chat Title")
        val updatedChat = repository.getChat(chatId)
        assertEquals("Title should be updated", "Updated Chat Title", updatedChat?.title)

        // Move chat to different folder
        val newFolderId = repository.createFolder("New Folder")
        repository.moveChatToFolder(chatId, newFolderId)
        val movedChat = repository.getChat(chatId)
        assertEquals("Should be in new folder", newFolderId, movedChat?.folderId)

        // Delete chat (should cascade delete messages)
        repository.deleteChat(chatId)
        val deletedChat = repository.getChat(chatId)
        assertNull("Chat should be deleted", deletedChat)

        val remainingMessages = repository.listMessages(chatId)
        assertTrue("Messages should be cascade deleted", remainingMessages.isEmpty())
    }

    @Test
    fun `document and RAG integration test`() = runBlocking {
        // Create document
        val document = Document(
            uri = "content://test/document/1",
            title = "Test Document",
            hash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            mime = "text/plain",
            textBytes = "This is a test document about artificial intelligence and machine learning.".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val docId = repository.upsertDocument(document)

        // Create embeddings and chunks (simulate RAG indexing)
        val chunks = listOf(
            RagChunk(
                docId = docId,
                start = 0,
                end = 30,
                text = "This is a test document about",
                tokenCount = 6,
                embeddingId = null
            ),
            RagChunk(
                docId = docId,
                start = 31,
                end = 75,
                text = "artificial intelligence and machine learning",
                tokenCount = 6,
                embeddingId = null
            )
        )

        val chunkIds = chunks.map { repository.insertRagChunk(it) }

        // Test document retrieval
        val retrievedDoc = repository.getDocument(docId)
        assertNotNull("Document should exist", retrievedDoc)
        assertEquals("Title should match", "Test Document", retrievedDoc?.title)

        // Test RAG search
        val searchResults = repository.searchChunks("artificial intelligence", limit = 10)
        assertTrue("Should find relevant chunks", searchResults.isNotEmpty())
        assertTrue("Should contain AI text", searchResults.any { chunk -> chunk.text.contains("artificial intelligence") })

        // Test chunk retrieval by document
        val docChunks = repository.getChunksByDocument(docId)
        assertEquals("Should have 2 chunks", 2, docChunks.size)

        // Delete document (should cascade delete chunks)
        repository.deleteDocument(docId)
        val deletedDoc = repository.getDocument(docId)
        assertNull("Document should be deleted", deletedDoc)

        val remainingChunks = repository.getChunksByDocument(docId)
        assertTrue("Chunks should be cascade deleted", remainingChunks.isEmpty())
    }

    @Test
    fun `folder management integration test`() = runBlocking {
        // Create folders
        val folder1Id = repository.createFolder("Folder 1")
        val folder2Id = repository.createFolder("Folder 2")
        val subfolderId = repository.createFolder("Subfolder")

        // Create chats in folders
        val chat1Id = repository.createChat(folder1Id, "Chat 1", "System", "model1")
        val chat2Id = repository.createChat(folder1Id, "Chat 2", "System", "model1")
        val chat3Id = repository.createChat(folder2Id, "Chat 3", "System", "model1")

        // Test folder listing
        val folders = repository.listFolders()
        assertEquals("Should have 3 folders", 3, folders.size)

        // Test chats by folder
        val folder1Chats = repository.listChatsByFolder(folder1Id)
        assertEquals("Folder 1 should have 2 chats", 2, folder1Chats.size)

        val folder2Chats = repository.listChatsByFolder(folder2Id)
        assertEquals("Folder 2 should have 1 chat", 1, folder2Chats.size)

        // Move chat between folders
        repository.moveChatToFolder(chat1Id, folder2Id)
        val updatedFolder1Chats = repository.listChatsByFolder(folder1Id)
        val updatedFolder2Chats = repository.listChatsByFolder(folder2Id)
        assertEquals("Folder 1 should now have 1 chat", 1, updatedFolder1Chats.size)
        assertEquals("Folder 2 should now have 2 chats", 2, updatedFolder2Chats.size)

        // Delete folder with chats (should set chat folderId to null)
        repository.deleteFolder(folder1Id)
        val remainingFolder1Chats = repository.listChatsByFolder(folder1Id)
        assertTrue("Folder should be gone", remainingFolder1Chats.isEmpty())

        val orphanedChats = repository.listChatsByFolder(null)
        assertTrue("Should have orphaned chats", orphanedChats.isNotEmpty())
    }

    @Test
    fun `data integrity and constraints test`() = runBlocking {
        // Test foreign key constraints
        val chatId = repository.createChat(null, "Test Chat", "System", "model")

        // Try to insert message with non-existent chat ID
        val invalidMessage = Message(
            chatId = 99999L, // Non-existent chat
            role = "user",
            contentMarkdown = "Test",
            tokens = 1,
            ttfsMs = 0,
            tps = 0f,
            contextUsedPct = 0f,
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        try {
            repository.insertMessage(invalidMessage)
            fail("Should fail foreign key constraint")
        } catch (e: Exception) {
            // Expected to fail due to foreign key constraint
            assertTrue("Should be constraint violation", e.message?.contains("FOREIGN KEY") == true ||
                    e.message?.contains("constraint") == true)
        }

        // Test valid insertion
        val validMessage = invalidMessage.copy(chatId = chatId)
        val messageId = repository.insertMessage(validMessage)
        assertTrue("Valid message should be inserted", messageId > 0)

        // Verify message exists
        val messages = repository.listMessages(chatId)
        assertEquals("Should have one message", 1, messages.size)
    }

    @Test
    fun `performance and statistics test`() = runBlocking {
        // Create test data
        val chatId = repository.createChat(null, "Performance Test", "System", "model")

        val messages = List(100) { index ->
            Message(
                chatId = chatId,
                role = if (index % 2 == 0) "user" else "assistant",
                contentMarkdown = "Message $index with some content to make it longer",
                tokens = 10,
                ttfsMs = 100L,
                tps = 100f,
                contextUsedPct = 0.1f * index,
                createdAt = System.currentTimeMillis() + index * 1000,
                metaJson = "{}"
            )
        }

        val startTime = System.currentTimeMillis()
        messages.forEach { repository.insertMessage(it) }
        val insertTime = System.currentTimeMillis() - startTime

        // Test retrieval performance
        val retrieveStart = System.currentTimeMillis()
        val retrievedMessages = repository.listMessages(chatId)
        val retrieveTime = System.currentTimeMillis() - retrieveStart

        assertEquals("Should have all messages", 100, retrievedMessages.size)
        assertTrue("Insert should be reasonably fast", insertTime < 5000) // Less than 5 seconds
        assertTrue("Retrieve should be fast", retrieveTime < 1000) // Less than 1 second

        // Test statistics
        val stats = repository.getChatStats(chatId)
        assertNotNull("Should have statistics", stats)
        assertEquals("Should count messages correctly", 100, stats?.messageCount)
        assertEquals("Should sum tokens correctly", 1000, stats?.totalTokens) // 100 * 10
    }
}
