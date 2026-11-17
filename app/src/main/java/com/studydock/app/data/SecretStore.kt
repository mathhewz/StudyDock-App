package com.studydock.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SecretStatus {
    VALID,
    MISSING,
    CORRUPTED_KEYSTORE
}

data class SecretReadResult(
    val value: String?,
    val status: SecretStatus
)

object SecretStore {
    private const val TAG = "SecretStore"
    private const val STORE = "studydock_secure_settings"
    private const val KEY_ALIAS = "studydock_backend_token_v1"
    private val aliasCleanRegex = Regex("[^a-z0-9_.-]")

    val DEFAULT_KEYS: List<String> = listOf(
        "assistant_gemini_key",
        "assistant_openai_key",
        "assistant_access_token"
    )

    fun read(context: Context, name: String): String? = readWithStatus(context, name).value

    fun readWithStatus(context: Context, name: String): SecretReadResult {
        val encoded = context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(name, null)
            ?: return SecretReadResult(value = null, status = SecretStatus.MISSING)
        val parts = encoded.split(':', limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            StudyLog.w(TAG, "Malformed secret entry for key '$name'")
            return SecretReadResult(value = null, status = SecretStatus.CORRUPTED_KEYSTORE)
        }
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val plaintext = decryptWithAlias(aliasFor(name), iv, encrypted)
                ?: decryptWithLegacyAliasIfPresent(iv, encrypted)
            SecretReadResult(value = plaintext, status = SecretStatus.VALID)
        } catch (e: Exception) {
            StudyLog.w(TAG, "Failed to decrypt secret '$name' (${e::class.java.simpleName})", e)
            SecretReadResult(value = null, status = SecretStatus.CORRUPTED_KEYSTORE)
        }
    }

    fun write(context: Context, name: String, value: String) {
        try {
            val cipher = initEncryptCipher(name)
            val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
            val encrypted = Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().putString(name, "$iv:$encrypted").apply()
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to encrypt secret '$name' (${e::class.java.simpleName})", e)
            throw e
        }
    }

    fun remove(context: Context, name: String) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit()
            .remove(name)
            .apply()
    }

    fun hasCorruptedSecrets(context: Context, names: Collection<String> = DEFAULT_KEYS): Boolean =
        names.any { name -> readWithStatus(context, name).status == SecretStatus.CORRUPTED_KEYSTORE }

    private fun aliasFor(name: String): String {
        val clean = name.lowercase().replace(aliasCleanRegex, "_")
        return "${KEY_ALIAS}_$clean"
    }

    private fun decryptWithAlias(alias: String, iv: ByteArray, encrypted: ByteArray): String? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(alias = alias, createIfMissing = false) ?: return null, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun decryptWithLegacyAliasIfPresent(iv: ByteArray, encrypted: ByteArray): String {
        val legacyKey = secretKey(alias = KEY_ALIAS, createIfMissing = false)
            ?: throw GeneralSecurityException("No keystore key available for secret")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun initEncryptCipher(name: String): Cipher {
        val alias = aliasFor(name)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey(alias = alias, forceRegenerate = false, createIfMissing = true)!!)
            }
        } catch (e: GeneralSecurityException) {
            StudyLog.w(TAG, "Keystore key unusable during encrypt for '$name' (${e::class.java.simpleName}); regenerating per-secret alias", e)
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey(alias = alias, forceRegenerate = true, createIfMissing = true)!!)
            }
        }
    }

    private fun secretKey(
        alias: String = KEY_ALIAS,
        forceRegenerate: Boolean = false,
        createIfMissing: Boolean = true
    ): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (forceRegenerate) {
            runCatching { store.deleteEntry(alias) }
                .onFailure { StudyLog.w(TAG, "Failed to delete broken keystore alias '$alias'", it) }
        } else {
            val existing = runCatching { store.getKey(alias, null) }.getOrNull()
            if (existing is SecretKey) return existing
            if (!createIfMissing) return null
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }
}
