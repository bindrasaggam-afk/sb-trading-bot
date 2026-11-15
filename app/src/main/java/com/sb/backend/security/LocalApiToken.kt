package com.sb.backend.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object LocalApiToken {

    const val HEADER_NAME = "X-SB-Token"

    private const val PREFS_NAME = "sb_local_api_auth"
    private const val KEY_TOKEN = "access_token"
    private const val TOKEN_BYTES = 32

    @Synchronized
    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing

        val created = createToken()
        prefs.edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    fun peek(context: Context): String? {
        return context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).getString(KEY_TOKEN, null)
    }

    @Synchronized
    fun reset(context: Context): String {
        val created = createToken()
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    private fun createToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }
}
