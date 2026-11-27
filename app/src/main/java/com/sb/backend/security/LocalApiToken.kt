package com.sb.backend.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalApiTokens(
    val readOnly: String,
    val admin: String
)

object LocalApiToken {

    const val HEADER_NAME = "X-SB-Token"
    const val ADMIN_HEADER_NAME = "X-SB-Admin-Token"

    private const val PREFS_NAME = "sb_local_api_auth"
    private const val LEGACY_TOKEN_KEY = "access_token"
    private const val READ_TOKEN_KEY = "read_token_ciphertext"
    private const val READ_IV_KEY = "read_token_iv"
    private const val ADMIN_TOKEN_KEY = "admin_token_ciphertext"
    private const val ADMIN_IV_KEY = "admin_token_iv"
    private const val ISSUED_AT_KEY = "token_issued_at"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "sb_local_api_token_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    @Synchronized
    fun get(context: Context): String = tokens(context).admin

    @Synchronized
    fun tokens(context: Context): LocalApiTokens {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val issuedAt = prefs.getLong(ISSUED_AT_KEY, 0L)
        val legacy = prefs.getString(LEGACY_TOKEN_KEY, null)
        val encryptedAdmin = decrypt(
            prefs.getString(ADMIN_TOKEN_KEY, null),
            prefs.getString(ADMIN_IV_KEY, null)
        )
        val encryptedRead = decrypt(
            prefs.getString(READ_TOKEN_KEY, null),
            prefs.getString(READ_IV_KEY, null)
        )

        val admin = encryptedAdmin ?: legacy
        if (admin.isNullOrBlank() && encryptedRead.isNullOrBlank()) {
            return rotateLocked(prefs, now)
        }

        if (issuedAt <= 0L) {
            val result = LocalApiTokens(
                readOnly = encryptedRead ?: createToken(),
                admin = admin ?: createToken()
            )
            saveLocked(prefs, result, now)
            return result
        }

        if (ApiTokenPolicy.isExpired(issuedAt, now)) {
            return rotateLocked(prefs, now)
        }

        if (admin.isNullOrBlank() || encryptedRead.isNullOrBlank()) {
            val result = LocalApiTokens(
                readOnly = encryptedRead ?: createToken(),
                admin = admin ?: createToken()
            )
            saveLocked(prefs, result, issuedAt)
            return result
        }

        if (legacy != null) {
            saveLocked(
                prefs,
                LocalApiTokens(encryptedRead, admin),
                issuedAt
            )
        }
        return LocalApiTokens(encryptedRead, admin)
    }

    @Synchronized
    fun peek(context: Context): String? {
        val prefs = prefs(context)
        return decrypt(
            prefs.getString(ADMIN_TOKEN_KEY, null),
            prefs.getString(ADMIN_IV_KEY, null)
        ) ?: prefs.getString(LEGACY_TOKEN_KEY, null)
    }

    @Synchronized
    fun reset(context: Context): String =
        rotateLocked(prefs(context), System.currentTimeMillis()).admin

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit()
            .remove(LEGACY_TOKEN_KEY)
            .remove(READ_TOKEN_KEY)
            .remove(READ_IV_KEY)
            .remove(ADMIN_TOKEN_KEY)
            .remove(ADMIN_IV_KEY)
            .remove(ISSUED_AT_KEY)
            .commit()
    }

    private fun rotateLocked(
        prefs: SharedPreferences,
        issuedAt: Long
    ): LocalApiTokens {
        val tokens = LocalApiTokens(
            readOnly = createToken(),
            admin = createToken()
        )
        saveLocked(prefs, tokens, issuedAt)
        return tokens
    }

    private fun saveLocked(
        prefs: SharedPreferences,
        tokens: LocalApiTokens,
        issuedAt: Long
    ) {
        val read = encrypt(tokens.readOnly)
        val admin = encrypt(tokens.admin)
        val saved = prefs.edit()
            .putString(READ_TOKEN_KEY, read.first)
            .putString(READ_IV_KEY, read.second)
            .putString(ADMIN_TOKEN_KEY, admin.first)
            .putString(ADMIN_IV_KEY, admin.second)
            .putLong(ISSUED_AT_KEY, issuedAt)
            .remove(LEGACY_TOKEN_KEY)
            .commit()
        check(saved) { "Unable to persist API credentials" }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private fun createToken(): String {
        val bytes = ByteArray(ApiTokenPolicy.TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(
            encrypted,
            Base64.NO_WRAP
        ) to Base64.encodeToString(
            cipher.iv,
            Base64.NO_WRAP
        )
    }

    private fun decrypt(
        ciphertext: String?,
        iv: String?
    ): String? {
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(
                    GCM_TAG_BITS,
                    Base64.decode(iv, Base64.NO_WRAP)
                )
            )
            String(
                cipher.doFinal(
                    Base64.decode(ciphertext, Base64.NO_WRAP)
                ),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
            load(null)
        }
        val existing = keyStore.getEntry(KEY_ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) {
            return existing.secretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
