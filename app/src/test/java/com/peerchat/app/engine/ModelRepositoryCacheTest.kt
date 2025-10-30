package com.peerchat.app.engine

import android.app.Application
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRepositoryCacheTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var application: Application
    private lateinit var manifestService: ModelManifestService
    private lateinit var repository: ModelRepository

    @Before
    fun setUp() {
        mockkObject(EngineRuntime)
        coEvery { EngineRuntime.captureState() } returns "state".toByteArray()
        coEvery { EngineRuntime.restoreState(any()) } returns true
        coEvery { EngineRuntime.load(any()) } returns true
        every { EngineRuntime.updateMetricsFromNative() } returns EngineMetrics.empty()
        coEvery { EngineRuntime.unload() } returns Unit
        coEvery { EngineRuntime.clearState(any()) } returns Unit

        val filesRoot = tempDir.newFolder("files")
        application = object : Application() {
            override fun getFilesDir(): File = filesRoot
            override fun getApplicationContext(): Application = this
        }

        manifestService = mockk(relaxed = true)
        coEvery { manifestService.list() } returns emptyList()

        repository = ModelRepository(
            context = application,
            manifestService = manifestService,
            maxCacheFiles = 3,
            maxCacheBytes = 2L * 1024L
        )
    }

    @After
    fun tearDown() {
        unmockkObject(EngineRuntime)
    }

    @Test
    fun `capture followed by restore delegates to engine`() = runTest {
        repository.captureKv(chatId = 1L)
        val restored = repository.restoreKv(chatId = 1L)

        assertTrue(restored)
        coVerify { EngineRuntime.restoreState("state".toByteArray()) }
        assertTrue(repository.cacheStats.value.hits >= 1)
    }

    @Test
    fun `restore miss removes corrupt entry`() = runTest {
        repository.captureKv(1L)
        coEvery { EngineRuntime.restoreState(any()) } returns false

        val restored = repository.restoreKv(1L)
        assertFalse(restored)
        val cacheFile = File(application.filesDir, "kv_cache/chat_1.kvc")
        assertFalse(cacheFile.exists())
        assertTrue(repository.cacheStats.value.misses >= 1)
    }

    @Test
    fun `lru eviction removes oldest entries`() = runTest {
        coEvery { EngineRuntime.captureState() } returnsMany listOf(
            "a".repeat(300).toByteArray(),
            "b".repeat(300).toByteArray(),
            "c".repeat(300).toByteArray(),
            "d".repeat(300).toByteArray()
        )

        repository.captureKv(1L)
        repository.captureKv(2L)
        repository.captureKv(3L)
        repository.restoreKv(1L) // bump chat 1 to most recent

        repository.captureKv(4L) // forces eviction of chat 2

        val files = File(application.filesDir, "kv_cache").list()?.sorted() ?: emptyList()
        assertTrue(files.contains("chat_1.kvc"))
        assertFalse(files.contains("chat_2.kvc"))
        assertTrue(repository.cacheStats.value.evictions >= 1)
    }
}
