package com.sb.backend.security

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object DeviceIdentity {

    private const val PREFS_NAME = "sb_backend_identity"
    private const val KEY_DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val created = create(context)
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun getOrCreate(context: Context): String = get(context)

    fun peek(context: Context): String? {
        return context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).getString(KEY_DEVICE_ID, null)
    }

    fun reset(context: Context): String {
        val created = create(context)
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            builder.append(
                String.format(Locale.US, "%02x", byte.toInt() and 0xff)
            )
        }
        return builder.toString()
    }

    private fun create(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            null
        }

        val seed = UUID.randomUUID().toString() +
            ":" +
            (androidId ?: "unknown") +
            ":" +
            System.currentTimeMillis()
        return hash(seed)
    }
}
