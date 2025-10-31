package com.peerchat.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Utility function to convert FloatArray to ByteArray
private fun FloatArray.toByteArray(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (value in this) {
        buffer.putFloat(value)
    }
    return buffer.array()
}

class DatabaseTest {

    private lateinit var database: PeerDatabase
    private lateinit var folderDao: FolderDao
    private lateinit var chatDao: ChatDao
    private lateinit var messageDao: MessageDao
    private lateinit var documentDao: DocumentDao
    private lateinit var embeddingDao: EmbeddingDao
    private lateinit var ragDao: RagDao
    private lateinit var modelDao: ModelManifestDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PeerDatabase::class.java)
            .allowMainThreadQueries() // For testing only
            .build()

        folderDao = database.folderDao()
        chatDao = database.chatDao()
        messageDao = database.messageDao()
        documentDao = database.documentDao()
        embeddingDao = database.embeddingDao()
        ragDao = database.ragDao()
        modelDao = database.modelManifestDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `folder operations work correctly`() = runBlocking {
        // Create folder
        val folder = Folder(
            name = "Test Folder",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val folderId = folderDao.upsert(folder)
        assertTrue("Folder ID should be positive", folderId > 0)

        // Read folder
        val folders = runBlocking { folderDao.observeAll().first() }
        assertEquals("Should have one folder", 1, folders.size)
        assertEquals("Folder name should match", "Test Folder", folders[0].name)

        // Update folder
        val now = System.currentTimeMillis()
        folderDao.rename(folderId, "Updated Folder", now)

        val updatedFolders = runBlocking { folderDao.observeAll().first() }
        assertEquals("Updated name should match", "Updated Folder", updatedFolders[0].name)

        // Delete folder
        folderDao.deleteById(folderId)
        val emptyFolders = runBlocking { folderDao.observeAll().first() }
        assertTrue("Should be empty after deletion", emptyFolders.isEmpty())
    }

    @Test
    fun `chat operations work correctly`() = runBlocking {
        // Create folder first
        val folderId = folderDao.upsert(Folder(
            name = "Test Folder",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        ))

        // Create chat
        val chat = Chat(
            folderId = folderId,
            title = "Test Chat",
            systemPrompt = "You are helpful",
            modelId = "llama-7b",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{\"temperature\": 0.7}"
        )

        val chatId = chatDao.upsert(chat)
        assertTrue("Chat ID should be positive", chatId > 0)

        // Read chat
        val retrievedChat = chatDao.getById(chatId)
        assertNotNull("Chat should exist", retrievedChat)
        assertEquals("Chat title should match", "Test Chat", retrievedChat?.title)

        // Update chat
        val now = System.currentTimeMillis()
        chatDao.rename(chatId, "Updated Chat", now)

        val updatedChat = chatDao.getById(chatId)
        assertEquals("Updated title should match", "Updated Chat", updatedChat?.title)

        // Test folder operations
        chatDao.moveToFolder(chatId, null, now)
        val movedChat = chatDao.getById(chatId)
        assertNull("Chat should not have folder", movedChat?.folderId)

        // Delete chat
        chatDao.deleteById(chatId)
        val deletedChat = chatDao.getById(chatId)
        assertNull("Chat should be deleted", deletedChat)
    }

    @Test
    fun `message operations work correctly`() = runBlocking {
        // Create folder and chat first
        val folderId = folderDao.upsert(Folder(
            name = "Test Folder",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        ))

        val chat = Chat(
            folderId = folderId,
            title = "Test Chat",
            systemPrompt = "You are helpful",
            modelId = "llama-7b",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{}"
        )
        val chatId = chatDao.upsert(chat)

        // Create messages
        val userMessage = Message(
            chatId = chatId,
            role = "user",
            contentMarkdown = "Hello",
            tokens = 1,
            ttfsMs = 0,
            tps = 0f,
            contextUsedPct = 0f,
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val assistantMessage = Message(
            chatId = chatId,
            role = "assistant",
            contentMarkdown = "Hi there!",
            tokens = 2,
            ttfsMs = 150,
            tps = 13.3f,
            contextUsedPct = 0.1f,
            createdAt = System.currentTimeMillis() + 1000,
            metaJson = "{\"reasoning\": \"Greeting user\"}"
        )

        val userMsgId = messageDao.insert(userMessage)
        val assistantMsgId = messageDao.insert(assistantMessage)

        // Test retrieval
        val messages = messageDao.listByChat(chatId)
        assertEquals("Should have 2 messages", 2, messages.size)
        assertEquals("First message should be user", "user", messages[0].role)
        assertEquals("Second message should be assistant", "assistant", messages[1].role)

        // Test last message
        val lastMessage = messageDao.getLastMessage(chatId)
        assertNotNull("Should have last message", lastMessage)
        assertEquals("Last message should be assistant", "assistant", lastMessage?.role)

        // Test count
        val count = messageDao.countByChat(chatId)
        assertEquals("Should count correctly", 2, count)

        // Test tokens
        val totalTokens = messageDao.getTotalTokens(chatId)
        assertEquals("Should sum tokens correctly", 3, totalTokens)

        // Delete messages
        messageDao.deleteByChat(chatId)
        val emptyMessages = messageDao.listByChat(chatId)
        assertTrue("Should be empty after deletion", emptyMessages.isEmpty())
    }

    @Test
    fun `document operations work correctly`() = runBlocking {
        val document = Document(
            uri = "content://test/document/1",
            title = "Test Document",
            hash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            mime = "text/plain",
            textBytes = "Hello world".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )

        val docId = documentDao.upsert(document)
        assertTrue("Document ID should be positive", docId > 0)

        // Test retrieval
        val documents = runBlocking { documentDao.observeAll().first() }
        assertEquals("Should have one document", 1, documents.size)
        assertEquals("Title should match", "Test Document", documents[0].title)

        // Test count
        val count = documentDao.countDocuments()
        assertEquals("Should count correctly", 1, count)

        // Delete document
        documentDao.delete(docId)
        val emptyDocs = runBlocking { documentDao.observeAll().first() }
        assertTrue("Should be empty after deletion", emptyDocs.isEmpty())
    }

    @Test
    fun `embedding operations work correctly`() = runBlocking {
        // Create document first
        val docId = documentDao.upsert(Document(
            uri = "content://test/doc/1",
            title = "Test Doc",
            hash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            mime = "text/plain",
            textBytes = "test".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        ))

        // Create embedding
        val vector = FloatArray(768) { 0.1f }
        val embedding = Embedding(
            docId = docId,
            chatId = null,
            textHash = "b665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            vector = vector.toByteArray(),
            dim = 768,
            norm = 1.0f,
            createdAt = System.currentTimeMillis()
        )

        val embeddingId = embeddingDao.upsert(embedding)
        assertTrue("Embedding ID should be positive", embeddingId > 0)

        // Test retrieval
        val embeddings = embeddingDao.getByDocId(docId)
        assertEquals("Should have one embedding", 1, embeddings.size)
        assertEquals("Dimension should match", 768, embeddings[0].dim)

        // Test by text hash
        val byHash = embeddingDao.getByTextHash(embedding.textHash)
        assertNotNull("Should find by hash", byHash)
        assertEquals("Hash should match", embedding.textHash, byHash?.textHash)

        // Test counts
        val totalCount = embeddingDao.count()
        assertEquals("Total count should be 1", 1, totalCount)

        val docCount = embeddingDao.countByDocId(docId)
        assertEquals("Doc count should be 1", 1, docCount)

        // Delete by document
        embeddingDao.deleteByDocId(docId)
        val emptyEmbeddings = embeddingDao.getByDocId(docId)
        assertTrue("Should be empty after deletion", emptyEmbeddings.isEmpty())
    }

    @Test
    fun `rag chunk operations work correctly`() = runBlocking {
        // Create document first
        val docId = documentDao.upsert(Document(
            uri = "content://test/doc/1",
            title = "Test Doc",
            hash = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            mime = "text/plain",
            textBytes = "test".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        ))

        // Create chunk
        val chunk = RagChunk(
            docId = docId,
            start = 0,
            end = 4,
            text = "test",
            tokenCount = 1,
            embeddingId = null
        )

        val chunkId = ragDao.insertChunk(chunk)
        assertTrue("Chunk ID should be positive", chunkId > 0)

        // Test retrieval
        val chunks = ragDao.searchChunks("test", limit = 10)
        assertTrue("Should find chunk", chunks.isNotEmpty())

        // Test count
        val count = ragDao.countChunks()
        assertEquals("Should count correctly", 1, count)

        // Test average tokens
        val avgTokens = ragDao.getAverageTokenCount()
        assertNotNull("Should have average", avgTokens)
        assertEquals("Average should be 1.0", 1.0f, avgTokens!!, 0.1f)
    }

    @Test
    fun `model manifest operations work correctly`() = runBlocking {
        val manifest = ModelManifest(
            name = "llama-7b-q4",
            filePath = "/models/llama-7b-q4.gguf",
            family = "llama",
            sizeBytes = 1024 * 1024 * 3800L, // ~3.8GB
            checksumSha256 = "a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3",
            contextLength = 4096,
            importedAt = System.currentTimeMillis(),
            sourceUrl = "https://huggingface.co/test/model",
            metadataJson = "{\"quantization\": \"Q4_0\"}",
            isDefault = true
        )

        val manifestId = modelDao.upsert(manifest)
        assertTrue("Manifest ID should be positive", manifestId > 0)

        // Test retrieval
        val manifests = modelDao.listAll()
        assertEquals("Should have one manifest", 1, manifests.size)
        assertEquals("Name should match", "llama-7b-q4", manifests[0].name)

        // Test by name
        val byName = modelDao.getByName("llama-7b-q4")
        assertNotNull("Should find by name", byName)
        assertEquals("Family should match", "llama", byName?.family)

        // Update metadata
        modelDao.updateMetadata(manifestId, "{\"updated\": true}")

        val updated = modelDao.getByName("llama-7b-q4")
        assertTrue("Should have updated metadata", updated?.metadataJson?.contains("updated") == true)

        // Delete
        modelDao.deleteById(manifestId)
        val emptyManifests = modelDao.listAll()
        assertTrue("Should be empty after deletion", emptyManifests.isEmpty())
    }

    @Test
    fun `foreign key constraints work correctly`() = runBlocking {
        // Create folder and chat
        val folderId = folderDao.upsert(Folder(
            name = "Test Folder",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        ))

        val chat = Chat(
            folderId = folderId,
            title = "Test Chat",
            systemPrompt = "Test",
            modelId = "test",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{}"
        )
        val chatId = chatDao.upsert(chat)

        // Add message
        val messageId = messageDao.insert(Message(
            chatId = chatId,
            role = "user",
            contentMarkdown = "Test",
            tokens = 1,
            ttfsMs = 0,
            tps = 0f,
            contextUsedPct = 0f,
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        ))

        // Verify message exists
        val messagesBefore = messageDao.listByChat(chatId)
        assertEquals("Should have message before chat deletion", 1, messagesBefore.size)

        // Delete chat - should cascade delete messages
        chatDao.deleteById(chatId)

        // Verify message is gone
        val messagesAfter = messageDao.listByChat(chatId)
        assertTrue("Messages should be cascade deleted", messagesAfter.isEmpty())

        // Delete folder - should set chat folderId to null
        val chat2 = Chat(
            folderId = folderId,
            title = "Test Chat 2",
            systemPrompt = "Test",
            modelId = "test",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            settingsJson = "{}"
        )
        val chatId2 = chatDao.upsert(chat2)

        folderDao.deleteById(folderId)

        val updatedChat = chatDao.getById(chatId2)
        assertNull("Folder ID should be set to null", updatedChat?.folderId)
    }
}
