package com.peerchat.app.engine

import android.content.Context
import com.peerchat.data.db.ModelManifest
import com.peerchat.templates.TemplateCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.max

class ModelManifestService(private val context: Context) {
    private val repository = ModelManifestRepository(context)

    fun manifestsFlow(): Flow<List<ModelManifest>> = repository.observeManifests()

    suspend fun list(): List<ModelManifest> = repository.listManifests()

    suspend fun ensureManifestFor(
        path: String,
        modelMetaJson: String? = null,
        sourceUrl: String? = null,
        isDefault: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext

        val name = file.nameWithoutExtension
        val existing = repository.getByName(name)
        val metaObject = modelMetaJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: existing?.metadataJson?.let { runCatching { JSONObject(it) }.getOrNull() }

        val checksum = computeSha256(file)
        val family = extractFamily(metaObject) ?: existing?.family ?: "unknown"
        val contextLen = extractContextLength(metaObject) ?: existing?.contextLength ?: 0
        val metadata = (metaObject ?: JSONObject()).apply {
            put("checksum", checksum)
            put("fileExists", true)
            put("lastScanned", System.currentTimeMillis())
            val detectionSource = metaObject?.toString()
                ?: modelMetaJson
                ?: existing?.metadataJson
            val modelMetadata = TemplateCatalog.parseMetadata(detectionSource)
            val detectedTemplate = TemplateCatalog.detect(modelMetadata)
            put("detectedTemplateId", detectedTemplate)
            TemplateCatalog.resolve(detectedTemplate)?.let { template ->
                put("detectedTemplateLabel", template.displayName)
                put("detectedTemplateStops", template.stopSequences)
            }
            modelMetadata.arch?.let { putOpt("arch", it) }
            modelMetadata.chatTemplate?.let { putOpt("chatTemplate", it) }
            modelMetadata.tokenizerModel?.let { putOpt("tokenizerModel", it) }
            modelMetadata.tags?.let { putOpt("tags", it) }
        }

        val manifest = ModelManifest(
            id = existing?.id ?: 0,
            name = name,
            filePath = file.absolutePath,
            family = family,
            sizeBytes = file.length(),
            checksumSha256 = checksum,
            contextLength = contextLen,
            importedAt = existing?.importedAt ?: System.currentTimeMillis(),
            sourceUrl = sourceUrl ?: existing?.sourceUrl,
            metadataJson = metadata.toString(),
            isDefault = existing?.isDefault ?: isDefault,
        )
        repository.upsert(manifest)
    }

    suspend fun deleteManifest(manifest: ModelManifest, removeFile: Boolean) = withContext(Dispatchers.IO) {
        repository.delete(manifest.id)
        if (removeFile) {
            runCatching {
                val file = File(manifest.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    suspend fun refreshManifest(manifest: ModelManifest) = ensureManifestFor(
        path = manifest.filePath,
        modelMetaJson = manifest.metadataJson,
        sourceUrl = manifest.sourceUrl,
        isDefault = manifest.isDefault,
    )

    fun detectedTemplateId(manifest: ModelManifest): String? {
        return manifestMetadata(manifest)?.optString("detectedTemplateId")?.takeIf { it.isNotBlank() }
    }

    fun detectedTemplateLabel(manifest: ModelManifest): String? {
        return manifestMetadata(manifest)?.optString("detectedTemplateLabel")?.takeIf { it.isNotBlank() }
    }

    private fun manifestMetadata(manifest: ModelManifest): JSONObject? {
        return runCatching { JSONObject(manifest.metadataJson) }.getOrNull()
    }

    private fun computeSha256(file: File): String {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractFamily(meta: JSONObject?): String? {
        if (meta == null) return null
        val keys = listOf("general.architecture", "arch", "family", "detected_family")
        for (key in keys) {
            val value = meta.optString(key, "")
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun extractContextLength(meta: JSONObject?): Int? {
        if (meta == null) return null
        val keys = listOf("n_ctx_train", "context_length", "general.context_length")
        for (key in keys) {
            val value = meta.optInt(key, -1)
            if (value > 0) return value
        }
        return null
    }

    companion object {
        private val DEFAULT_BUFFER_SIZE = max(8 * 1024, 1 shl 15)
    }

    suspend fun verify(manifest: ModelManifest): Boolean = withContext(Dispatchers.IO) {
        val file = File(manifest.filePath)
        if (!file.exists()) return@withContext false
        val checksum = computeSha256(file)
        val meta = runCatching { JSONObject(manifest.metadataJson) }.getOrNull()?.apply {
            put("checksum", checksum)
            put("fileExists", true)
            put("lastScanned", System.currentTimeMillis())
        }?.toString() ?: manifest.metadataJson
        repository.upsert(
            manifest.copy(
                sizeBytes = file.length(),
                checksumSha256 = checksum,
                metadataJson = meta
            )
        )
        true
    }
}
