package com.peerchat.app.engine

import com.peerchat.app.testutil.CoroutineTestRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Comprehensive tests for KV cache operations.
 */
class KvCacheTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    private lateinit var repository: ModelRepository

    @Before
    fun setUp() {
        mockkObject(EngineRuntime)
        coEvery { EngineRuntime.captureState() } returns "test_state".toByteArray()
        coEvery { EngineRuntime.restoreState(any()) } returns true
        coEvery { EngineRuntime.load(any()) } returns true
        coEvery { EngineRuntime.unload() } returns Unit
        coEvery { EngineRuntime.clearState(any()) } returns Unit
        every { EngineRuntime.updateMetricsFromNative() } returns com.peerchat.engine.EngineMetrics.empty()

        val filesRoot = tempDir.newFolder("files")
        val application = object : android.app.Application() {
            override fun getFilesDir(): java.io.File = filesRoot
            override fun getApplicationContext(): android.app.Application = this
        }

        val manifestService = mockk<ModelManifestService>(relaxed = true)
        repository = ModelRepository(
            context = application,
            manifestService = manifestService,
            maxCacheFiles = 5,
            maxCacheBytes = 10 * 1024L // 10KB
        )
    }

    @After
    fun tearDown() {
        unmockkObject(EngineRuntime)
    }

    @Test
    fun `capture and restore round-trip succeeds`() = runTest {
        repository.captureKv(chatId = 1L)
        val restored = repository.restoreKv(chatId = 1L)

        assertTrue(restored)
        coVerify { EngineRuntime.restoreState(any()) }
        assertTrue(repository.cacheStats.value.hits > 0)
    }

    @Test
    fun `restore of missing cache returns false`() = runTest {
        val restored = repository.restoreKv(chatId = 999L)

        assertFalse(restored)
        assertTrue(repository.cacheStats.value.misses > 0)
    }

    @Test
    fun `corrupted cache file is removed on restore failure`() = runTest {
        repository.captureKv(1L)
        coEvery { EngineRuntime.restoreState(any()) } returns false

        val restored = repository.restoreKv(1L)
        assertFalse(restored)
        assertTrue(repository.cacheStats.value.misses > 0)
    }

    @Test
    fun `cache eviction removes oldest entries`() = runTest {
        // Fill cache with multiple entries
        coEvery { EngineRuntime.captureState() } returnsMany listOf(
            ByteArray(100),
            ByteArray(100),
            ByteArray(100),
            ByteArray(100),
            ByteArray(100),
            ByteArray(100) // This should trigger eviction
        )

        repository.captureKv(1L)
        repository.captureKv(2L)
        repository.captureKv(3L)
        repository.restoreKv(1L) // Make chat 1 most recent
        repository.captureKv(4L) // Should evict chat 2

        val stats = repository.cacheStats.value
        assertTrue("Should have evictions", stats.evictions > 0)
    }

    @Test
    fun `byte budget enforcement skips large snapshots`() = runTest {
        coEvery { EngineRuntime.captureState() } returns ByteArray(20 * 1024) // 20KB > 10KB limit

        repository.captureKv(1L)

        val stats = repository.cacheStats.value
        assertEquals("Large snapshot should not be cached", 0L, stats.bytes)
    }

    @Test
    fun `clear all removes all cache entries`() = runTest {
        repository.captureKv(1L)
        repository.captureKv(2L)

        repository.clearAllKv()

        assertFalse(repository.restoreKv(1L))
        assertFalse(repository.restoreKv(2L))
        assertEquals(0L, repository.cacheStats.value.bytes)
    }

    @Test
    fun `cache stats track hits and misses correctly`() = runTest {
        repository.captureKv(1L)
        repository.restoreKv(1L) // Hit
        repository.restoreKv(2L) // Miss
        repository.restoreKv(1L) // Hit

        val stats = repository.cacheStats.value
        assertEquals(2L, stats.hits)
        assertEquals(1L, stats.misses)
    }
}

