package com.peerchat.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * Secure database provider with SQLCipher encryption
 */
object SecureDatabaseProvider {

    private const val DATABASE_NAME = "peerchat.db"
    private const val ENCRYPTION_KEY_ALIAS = "peerchat_db_key"

    /**
     * Get or create encrypted database instance
     */
    fun getDatabase(context: Context): PeerDatabase {
        // Initialize SQLCipher
        SQLiteDatabase.loadLibs(context)

        // Get encryption key from secure storage
        val encryptionKey = getOrCreateEncryptionKey(context)

        // Create encrypted database factory
        val factory = SupportFactory(encryptionKey)

        return Room.databaseBuilder(
            context.applicationContext,
            PeerDatabase::class.java,
            DATABASE_NAME
        )
        .openHelperFactory(factory)
        .addMigrations(
            PeerDatabaseMigrations.MIGRATION_1_2,
            PeerDatabaseMigrations.MIGRATION_2_3
        )
        .fallbackToDestructiveMigration() // Last resort for development
        .build()
    }

    /**
     * Get or create database encryption key using Android Keystore
     */
    private fun getOrCreateEncryptionKey(context: Context): ByteArray {
        return try {
            // Try to get existing key from encrypted shared preferences
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "db_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val storedKey = encryptedPrefs.getString(ENCRYPTION_KEY_ALIAS, null)
            if (storedKey != null) {
                // Decode hex string back to bytes
                storedKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            } else {
                // Generate new key
                val newKey = generateSecureKey()
                // Store as hex string
                val keyHex = newKey.joinToString("") { "%02x".format(it) }
                encryptedPrefs.edit().putString(ENCRYPTION_KEY_ALIAS, keyHex).apply()
                newKey
            }
        } catch (e: Exception) {
            // Fallback: generate a key based on device ID (less secure but better than no encryption)
            android.util.Log.w("SecureDatabaseProvider", "Failed to use keystore, using fallback key", e)
            generateFallbackKey(context)
        }
    }

    /**
     * Generate a secure 256-bit encryption key
     */
    private fun generateSecureKey(): ByteArray {
        val key = ByteArray(32)
        java.security.SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Generate a fallback key based on device properties (less secure)
     */
    private fun generateFallbackKey(context: Context): ByteArray {
        val deviceId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "fallback_device_id"

        // Use SHA-256 of device ID as key
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(deviceId.toByteArray()).copyOfRange(0, 32)
    }

    /**
     * Clear all database encryption keys (for testing/reset purposes)
     */
    fun clearEncryptionKeys(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "db_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            encryptedPrefs.edit().remove(ENCRYPTION_KEY_ALIAS).apply()
        } catch (e: Exception) {
            android.util.Log.w("SecureDatabaseProvider", "Failed to clear encryption keys", e)
        }
    }
}
