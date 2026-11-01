
package com.sb.backend.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KotakNeoSecureStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "kotak_neo_secure",
        Context.MODE_PRIVATE
    )

    private val alias = "SB_KOTAK_NEO_AES_KEY"

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        require(value.isNotEmpty()) {
            "Cannot encrypt an empty value"
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        return Base64.encodeToString(
            cipher.iv + encrypted,
            Base64.NO_WRAP
        )
    }

    private fun decryptSafely(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""

        return try {
            val combined = Base64.decode(stored, Base64.NO_WRAP)

            if (combined.size <= 12) return ""

            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")

            cipher.init(
                Cipher.DECRYPT_MODE,
                getKey(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(encrypted),
                Charsets.UTF_8
            )

        } catch (e: AEADBadTagException) {
            Log.e("KotakSecureStore", "Encrypted value validation failed")
            ""

        } catch (e: KeyPermanentlyInvalidatedException) {
            Log.e("KotakSecureStore", "Keystore key invalidated")
            ""

        } catch (e: Exception) {
            Log.e("KotakSecureStore", "Unable to decrypt stored value")
            ""
        }
    }

    private fun saveEncrypted(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit()
                .putString(key, encrypt(value))
                .apply()
        }
    }

    // API credentials

    fun save(
        accessToken: String,
        mobile: String,
        ucc: String
    ) {
        require(
            accessToken.isNotBlank() &&
            mobile.isNotBlank() &&
            ucc.isNotBlank()
        ) {
            "Kotak credentials cannot be empty"
        }

        prefs.edit()
            .putString("access_token", encrypt(accessToken))
            .putString("mobile", encrypt(mobile))
            .putString("ucc", encrypt(ucc))
            .apply()
    }

    fun getAccessToken(): String = decryptSafely("access_token")

    fun getMobile(): String = decryptSafely("mobile")

    fun getUcc(): String = decryptSafely("ucc")

    // TOTP secret

    fun saveTotpSecret(secret: String) {
        require(secret.isNotBlank()) {
            "TOTP secret missing"
        }

        saveEncrypted("totp_secret", secret.trim())
    }

    fun getTotpSecret(): String =
        decryptSafely("totp_secret")

    fun clearTotpSecret() {
        prefs.edit().remove("totp_secret").apply()
    }

    // MPIN

    fun saveMpin(mpin: String) {
        require(mpin.matches(Regex("\\d{6}"))) {
            "MPIN must be 6 digits"
        }

        saveEncrypted("mpin", mpin)
    }

    fun getMpin(): String = decryptSafely("mpin")

    fun clearMpin() {
        prefs.edit().remove("mpin").apply()
    }

    // Automatic login setting

    fun setAutoLoginEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("auto_login_enabled", enabled)
            .apply()
    }

    fun isAutoLoginEnabled(): Boolean =
        prefs.getBoolean("auto_login_enabled", false)

    // Trade session

    fun saveTradeSession(
        token: String,
        sid: String,
        baseUrl: String,
        feedUrl: String = ""
    ) {
        require(
            token.isNotBlank() &&
            sid.isNotBlank() &&
            baseUrl.isNotBlank()
        ) {
            "Trade session data incomplete"
        }

        val editor = prefs.edit()
            .putString("trade_token", encrypt(token))
            .putString("trade_sid", encrypt(sid))
            .putString("trade_base_url", encrypt(baseUrl))

        if (feedUrl.isNotBlank()) {
            editor.putString("trade_feed_url", encrypt(feedUrl))
        } else {
            editor.remove("trade_feed_url")
        }

        editor.apply()
    }

    fun getTradeToken(): String =
        decryptSafely("trade_token")

    fun getTradeSid(): String =
        decryptSafely("trade_sid")

    fun getTradeBaseUrl(): String =
        decryptSafely("trade_base_url")

    fun getTradeFeedUrl(): String {
        val saved = decryptSafely("trade_feed_url").trim()
        if (saved.isNotBlank()) return saved

        val baseUrl = getTradeBaseUrl().trim().trimEnd('/')
        if (baseUrl.isBlank()) return ""

        return if (baseUrl.endsWith("/apifeed")) {
            baseUrl
        } else {
            "$baseUrl/apifeed"
        }
    }

    fun clearTradeSession() {
        prefs.edit()
            .remove("trade_token")
            .remove("trade_sid")
            .remove("trade_base_url")
            .remove("trade_feed_url")
            .apply()
    }

    // Clear all saved data

    fun clear() {
        prefs.edit().clear().apply()
    }
}
