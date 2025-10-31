package com.peerchat.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.data.db.Document
import com.peerchat.data.db.Message
import com.peerchat.data.db.PeerDatabase
import com.peerchat.data.db.RagChunk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerChatRepositoryIntegrationTest {

    private lateinit var database: PeerDatabase
    private lateinit var repository: PeerChatRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PeerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PeerChatRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `chat lifecycle maintains data integrity`() = runBlocking {
        val folderId = repository.createFolder("Inbox")
        val chatId = repository.createChat(
            title = "Greeting Thread",
            folderId = folderId,
            systemPrompt = "You are helpful",
            modelId = "llama"
        )

        // Insert dialogue
        repository.insertMessage(
            Message(
                chatId = chatId,
                role = "user",
                contentMarkdown = "Hello?",
                tokens = 3,
                ttfsMs = 120,
                tps = 50f,
                contextUsedPct = 0.05f,
                createdAt = System.currentTimeMillis(),
                metaJson = "{}"
            )
        )
        repository.insertMessage(
            Message(
                chatId = chatId,
                role = "assistant",
                contentMarkdown = "Hi there!",
                tokens = 4,
                ttfsMs = 80,
                tps = 60f,
                contextUsedPct = 0.04f,
                createdAt = System.currentTimeMillis() + 1,
                metaJson = "{}"
            )
        )

        // Verify creation
        val chat = repository.getChat(chatId)
        assertNotNull(chat)
        assertEquals("Greeting Thread", chat?.title)
        assertEquals(folderId, chat?.folderId)

        val messages = repository.listMessages(chatId)
        assertEquals(2, messages.size)
        assertEquals("user", messages.first().role)
        assertEquals("assistant", messages.last().role)

        // Move to a new folder
        val archiveId = repository.createFolder("Archive")
        repository.moveChat(chatId, archiveId)
        val movedChat = repository.getChat(chatId)
        assertEquals(archiveId, movedChat?.folderId)

        // Delete chat and ensure cascade
        repository.deleteChat(chatId)
        val deletedMessages = repository.listMessages(chatId)
        assertTrue(deletedMessages.isEmpty())
    }

    @Test
    fun `document indexing surfaces chunks in search`() = runBlocking {
        val document = Document(
            uri = "content://docs/1",
            title = "AI Overview",
            hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            mime = "text/plain",
            textBytes = "Artificial intelligence and machine learning are connected.".toByteArray(),
            createdAt = System.currentTimeMillis(),
            metaJson = "{}"
        )
        val docId = repository.upsertDocument(document)

        val ragDao = repository.database().ragDao()
        ragDao.insertChunk(
            RagChunk(
                docId = docId,
                start = 0,
                end = 55,
                text = "Artificial intelligence and machine learning are connected.",
                tokenCount = 12,
                embeddingId = null
            )
        )

        val results = repository.searchChunks("machine learning", limit = 5)
        assertTrue(results.isNotEmpty())
        assertEquals(docId, results.first().docId)

        repository.deleteDocument(docId)
        val postDelete = repository.searchChunks("machine learning", limit = 5)
        assertTrue(postDelete.isEmpty())
    }
}
