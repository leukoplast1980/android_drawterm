package com.claude.drawterm.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved passwords, AES-GCM encrypted with a key that never leaves the
 * Android Keystore. The file is excluded from backups (res/xml); a copy
 * would be useless elsewhere anyway.
 */
class SecretStore(context: Context) {
    private val file = JsonFile(context.filesDir, FILE_NAME)

    fun get(id: String): String? = synchronized(JsonFile.lock) {
        val enc = read().optString(id).ifEmpty { return null }
        try {
            val b = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, IV_LEN))
            String(c.doFinal(b, IV_LEN, b.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(JsonFile.TAG, "cannot decrypt a saved password", e)
            null
        }
    }

    fun has(id: String): Boolean = synchronized(JsonFile.lock) { read().has(id) }

    fun put(id: String, password: String) = synchronized(JsonFile.lock) {
        try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(password.toByteArray(Charsets.UTF_8))
            file.write(read().put(id, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).toString())
        } catch (e: Exception) {
            Log.w(JsonFile.TAG, "cannot save a password", e)
        }
    }

    fun remove(id: String) = synchronized(JsonFile.lock) {
        val o = read()
        if (o.remove(id) != null) file.write(o.toString())
    }

    private fun read(): JSONObject =
        file.read()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        const val FILE_NAME = "secrets.json"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "drawterm-passwords"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
    }
}
