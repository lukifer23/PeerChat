package com.peerchat.app.engine

import android.content.Context
import com.peerchat.data.db.ModelManifest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManifestServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `ensureManifestFor computes checksum and context length`() = runTest {
        val mockContext = mockk<Context>(relaxed = true)
        val repository = mockk<ModelManifestRepository>(relaxed = true)
        val service = ModelManifestService(mockContext, repository)

        val file = temp.newFile("sample.gguf").apply { writeText("peerchat-model") }
        val metadata = JSONObject().put("context_length", 8192).toString()

        val captured = slot<ModelManifest>()
        coEvery { repository.getByName(any()) } returns null
        coEvery { repository.upsert(capture(captured)) } returns 1L

        service.ensureManifestFor(path = file.absolutePath, modelMetaJson = metadata)

        coVerify { repository.upsert(any()) }
        val manifest = captured.captured
        val expectedChecksum = sha(file)
        val metaJson = JSONObject(manifest.metadataJson)

        org.junit.Assert.assertEquals(8192, manifest.contextLength)
        org.junit.Assert.assertEquals(expectedChecksum, manifest.checksumSha256)
        org.junit.Assert.assertEquals(expectedChecksum, metaJson.optString("checksum"))
        org.junit.Assert.assertTrue(metaJson.optBoolean("fileExists"))
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
