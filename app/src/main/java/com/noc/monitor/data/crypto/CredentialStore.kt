package com.noc.monitor.data.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.noc.monitor.protocol.NocError
import com.noc.monitor.protocol.NocException
import java.util.UUID

/**
 * Stores device passwords in Android encrypted prefs backed by the Keystore.
 * Values are never written to logs.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    fun createId(): String = UUID.randomUUID().toString()

    fun save(credentialId: String, password: CharArray) {
        try {
            prefs.edit().putString(credentialId, String(password)).apply()
        } catch (t: Throwable) {
            throw NocException(NocError.Crypto("Unable to store credentials securely: ${t.message}"))
        }
    }

    fun get(credentialId: String): CharArray? {
        return try {
            prefs.getString(credentialId, null)?.toCharArray()
        } catch (t: Throwable) {
            throw NocException(NocError.Crypto("Unable to read credentials: ${t.message}"))
        }
    }

    fun delete(credentialId: String) {
        prefs.edit().remove(credentialId).apply()
    }

    companion object {
        private const val FILE = "noc_secure_credentials"

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
