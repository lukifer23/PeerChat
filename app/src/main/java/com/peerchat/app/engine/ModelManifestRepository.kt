package com.peerchat.app.engine

import android.content.Context
import com.peerchat.data.db.ModelManifest
import com.peerchat.data.db.PeerDatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ModelManifestRepository(context: Context) {
    private val dao = PeerDatabaseProvider.get(context).modelManifestDao()

    fun observeManifests(): Flow<List<ModelManifest>> = dao.observeAll()

    suspend fun listManifests(): List<ModelManifest> = withContext(Dispatchers.IO) {
        dao.listAll()
    }

    suspend fun upsert(manifest: ModelManifest): Long = withContext(Dispatchers.IO) {
        dao.upsert(manifest)
    }

    suspend fun getByName(name: String): ModelManifest? = withContext(Dispatchers.IO) {
        dao.getByName(name)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(id)
    }
}
