package com.sb.backend.security

import java.security.MessageDigest

object ApiTokenPolicy {

    const val TOKEN_BYTES = 32
    const val TOKEN_TTL_MILLIS = 180L * 24L * 60L * 60L * 1000L

    fun isExpired(
        issuedAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (issuedAtMillis <= 0L) return true
        return nowMillis - issuedAtMillis >= TOKEN_TTL_MILLIS
    }

    fun matches(provided: String?, expected: String?): Boolean {
        if (provided.isNullOrBlank() || expected.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }
}
