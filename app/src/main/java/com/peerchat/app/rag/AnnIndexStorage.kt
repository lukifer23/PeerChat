package com.peerchat.app.rag

import android.content.Context
import com.peerchat.app.util.Logger
import com.peerchat.rag.RagAnnSnapshot
import com.peerchat.rag.SerializedVector
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

object AnnIndexStorage {
    private const val FILE_NAME = "ann_index.bin"
    private const val MAGIC = 0x5043414E
    private const val VERSION = 1

    fun save(context: Context, snapshot: RagAnnSnapshot) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            DataOutputStream(BufferedOutputStream(file.outputStream())).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(snapshot.numPlanes)
                out.writeInt(snapshot.bucketSize)
                out.writeInt(snapshot.records.size)
                out.writeInt(snapshot.fallbackSize)
                snapshot.records.forEach { record ->
                    out.writeLong(record.id)
                    val vector = record.vector
                    out.writeInt(vector.size)
                    vector.forEach { value -> out.writeFloat(value) }
                    out.writeFloat(record.norm)
                }
            }
        }.onFailure {
            Logger.e("AnnIndexStorage: failed to save index", mapOf("error" to it.message), it)
        }
    }

    fun load(context: Context): RagAnnSnapshot? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val magic = input.readInt()
                if (magic != MAGIC) throw IllegalStateException("Invalid ANN index magic")
                val version = input.readInt()
                if (version != VERSION) throw IllegalStateException("Unsupported ANN index version $version")
                val numPlanes = input.readInt()
                val bucketSize = input.readInt()
                val recordCount = input.readInt()
                val fallbackSize = input.readInt()
                val records = ArrayList<SerializedVector>(recordCount)
                repeat(recordCount) {
                    val id = input.readLong()
                    val vectorSize = input.readInt()
                    val vector = FloatArray(vectorSize) { input.readFloat() }
                    val norm = input.readFloat()
                    records.add(SerializedVector(id, vector, norm))
                }
                RagAnnSnapshot(numPlanes, bucketSize, fallbackSize, records)
            }
        }.getOrElse { throwable ->
            Logger.e("AnnIndexStorage: failed to load index", mapOf("error" to throwable.message), throwable)
            runCatching { file.delete() }
            null
        }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            runCatching { file.delete() }
        }
    }
}
