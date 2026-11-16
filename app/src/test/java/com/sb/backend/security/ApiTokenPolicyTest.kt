package com.sb.backend.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTokenPolicyTest {

    @Test
    fun expiresExactlyAtTheTtlBoundary() {
        val issuedAt = 1_000_000L

        assertFalse(
            ApiTokenPolicy.isExpired(
                issuedAt,
                issuedAt + ApiTokenPolicy.TOKEN_TTL_MILLIS - 1L
            )
        )
        assertTrue(
            ApiTokenPolicy.isExpired(
                issuedAt,
                issuedAt + ApiTokenPolicy.TOKEN_TTL_MILLIS
            )
        )
    }

    @Test
    fun missingIssuedTimestampIsExpired() {
        assertTrue(ApiTokenPolicy.isExpired(0L, 1_000_000L))
        assertTrue(ApiTokenPolicy.isExpired(-1L, 1_000_000L))
    }

    @Test
    fun tokenTtlIsOneHundredEightyDays() {
        assertTrue(
            ApiTokenPolicy.TOKEN_TTL_MILLIS ==
                180L * 24L * 60L * 60L * 1000L
        )
    }

    @Test
    fun tokensMustMatchExactly() {
        assertTrue(
            ApiTokenPolicy.matches("same-token", "same-token")
        )
        assertFalse(
            ApiTokenPolicy.matches("same-token", "same-token ")
        )
        assertFalse(
            ApiTokenPolicy.matches("same-token", "different-token")
        )
    }

    @Test
    fun missingOrBlankTokensNeverMatch() {
        assertFalse(ApiTokenPolicy.matches(null, "token"))
        assertFalse(ApiTokenPolicy.matches("token", null))
        assertFalse(ApiTokenPolicy.matches("", ""))
        assertFalse(ApiTokenPolicy.matches("   ", "   "))
    }
}
