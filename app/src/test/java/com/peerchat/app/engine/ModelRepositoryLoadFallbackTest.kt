package com.peerchat.app.engine

import android.app.Application
import com.peerchat.app.data.OperationResult
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelRepositoryLoadFallbackTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var application: Application
    private lateinit var manifestService: ModelManifestService
    private lateinit var repository: ModelRepository
    private lateinit var modelFile: File
    private lateinit var manifest: ModelManifest
    private lateinit var statusFlow: MutableStateFlow<EngineRuntime.EngineStatus>

    @Before
    fun setUp() {
        mockkObject(EngineRuntime)
        mockkObject(ModelConfigStore)

        statusFlow = MutableStateFlow<EngineRuntime.EngineStatus>(EngineRuntime.EngineStatus.Idle)
        every { EngineRuntime.status } returns statusFlow
        coEvery { EngineRuntime.unload() } returns Unit
        coEvery { EngineRuntime.clearState(any()) } returns Unit
        every { EngineRuntime.updateMetricsFromNative() } returns EngineMetrics.empty()
        every { EngineRuntime.currentModelMeta() } returns "{}"

        every { ModelConfigStore.save(any(), any()) } returns Unit
        every { ModelConfigStore.clear(any()) } returns Unit
        every { ModelConfigStore.load(any()) } returns null

        val filesRoot = tempDir.newFolder("appFiles")
        application = object : Application() {
            override fun getFilesDir(): File = filesRoot
            override fun getApplicationContext(): Application = this
        }

        modelFile = tempDir.newFile("test-model.gguf").also { file ->
            file.outputStream().use { out ->
                out.write(ByteArray(1_100_000)) // > 1MB to satisfy validation
            }
        }

        manifest = ModelManifest(
            id = 1,
            name = "test-model",
            filePath = modelFile.absolutePath,
            family = "llama",
            sizeBytes = modelFile.length(),
            checksumSha256 = "checksum",
            contextLength = 4096,
            importedAt = System.currentTimeMillis(),
            sourceUrl = null,
            metadataJson = "{}",
            isDefault = false
        )

        manifestService = mockk(relaxed = true)
        coEvery { manifestService.list() } answers { listOf(manifest) }
        coEvery {
            manifestService.ensureManifestFor(
                path = any(),
                modelMetaJson = any(),
                sourceUrl = any(),
                isDefault = any()
            )
        } returns Unit

        repository = ModelRepository(
            context = application,
            manifestService = manifestService,
            maxCacheFiles = 2,
            maxCacheBytes = 1024L * 1024L
        )
    }

    @After
    fun tearDown() {
        unmockkObject(ModelConfigStore)
        unmockkObject(EngineRuntime)
    }

    @Test
    fun `falls back to cpu when vulkan attempts fail`() = runTest {
        coEvery {
            EngineRuntime.load(match { it.useVulkan && it.gpuLayers == 20 })
        } answers {
            statusFlow.value = EngineRuntime.EngineStatus.Error("Primary GPU load failed")
            false
        }
        coEvery {
            EngineRuntime.load(match { it.useVulkan && it.gpuLayers == 10 })
        } answers {
            statusFlow.value = EngineRuntime.EngineStatus.Error("Reduced GPU load failed")
            false
        }
        coEvery {
            EngineRuntime.load(match { !it.useVulkan })
        } answers {
            val cfg = firstArg<EngineRuntime.EngineConfig>()
            statusFlow.value = EngineRuntime.EngineStatus.Loaded(cfg)
            true
        }

        val config = StoredEngineConfig(
            modelPath = modelFile.absolutePath,
            threads = 6,
            contextLength = 4096,
            gpuLayers = 20,
            useVulkan = true
        )

        val result = repository.loadModel(config)

        assertTrue(result is OperationResult.Success)
        val success = result as OperationResult.Success
        assertTrue(success.message.contains("CPU execution", ignoreCase = true))
        coVerify { EngineRuntime.load(match { !it.useVulkan }) }
    }

    @Test
    fun `aggregates errors when all load attempts fail`() = runTest {
        coEvery {
            EngineRuntime.load(any())
        } answers {
            val cfg = firstArg<EngineRuntime.EngineConfig>()
            val reason = when {
                cfg.useVulkan && cfg.gpuLayers == 20 -> "Primary configuration could not load"
                cfg.useVulkan && cfg.gpuLayers < 20 -> "Reduced GPU configuration failed"
                else -> "CPU fallback unavailable"
            }
            statusFlow.value = EngineRuntime.EngineStatus.Error(reason)
            false
        }

        val config = StoredEngineConfig(
            modelPath = modelFile.absolutePath,
            threads = 6,
            contextLength = 4096,
            gpuLayers = 20,
            useVulkan = true
        )

        val result = repository.loadModel(config)

        assertTrue(result is OperationResult.Failure)
        val failure = result as OperationResult.Failure
        assertTrue(failure.error.contains("primary configuration", ignoreCase = true))
        assertTrue(failure.error.contains("CPU execution", ignoreCase = true))
        assertTrue(failure.error.contains("failed", ignoreCase = true))
        coVerify { EngineRuntime.load(match { !it.useVulkan }) }
    }
}
