package com.sb.backend.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSecurityPolicyTest {

    @Test
    fun inventoryContainsExpectedReadAndAdminEndpoints() {
        val read = ApiSecurityPolicy.endpoints
            .filter { it.access == ApiAccess.READ }
        val admin = ApiSecurityPolicy.endpoints
            .filter { it.access == ApiAccess.ADMIN }

        assertEquals(10, read.size)
        assertEquals(4, admin.size)
        assertTrue(
            admin.all {
                it.method == "POST" &&
                    it.operation in setOf(
                        ApiOperation.BACKUP,
                        ApiOperation.DESTRUCTIVE,
                        ApiOperation.NETWORK_JOB
                    )
            }
        )
        assertTrue(
            read.all {
                it.method == "GET" &&
                    it.operation in setOf(
                        ApiOperation.HEALTH,
                        ApiOperation.READ_ONLY
                    )
            }
        )
    }

    @Test
    fun inventoryContainsNoTradingOrOrderEndpoints() {
        assertTrue(
            ApiSecurityPolicy.endpoints.none {
                val value = "${it.method} ${it.path}".lowercase()
                value.contains("order") ||
                    value.contains("trade") ||
                    value.contains("live")
            }
        )
        assertTrue(
            ApiSecurityPolicy.endpoints.none {
                it.access == ApiAccess.PUBLIC
            }
        )
    }

    @Test
    fun optionsIsTheOnlyUnauthenticatedRequest() {
        assertTrue(ApiSecurityPolicy.requiresAuthentication("GET"))
        assertTrue(ApiSecurityPolicy.requiresAuthentication("POST"))
        assertFalse(ApiSecurityPolicy.requiresAuthentication("OPTIONS"))
    }

    @Test
    fun endpointLookupRespectsMethodAndPath() {
        assertNotNull(
            ApiSecurityPolicy.endpoint("GET", "/api/health")
        )
        assertNull(
            ApiSecurityPolicy.endpoint("POST", "/api/health")
        )
        assertNull(
            ApiSecurityPolicy.endpoint("GET", "/api/not-real")
        )
        assertEquals(
            405,
            ApiSecurityPolicy.statusCodeFor("POST", "/api/health")
        )
        assertEquals(
            404,
            ApiSecurityPolicy.statusCodeFor("GET", "/api/not-real")
        )
    }

    @Test
    fun adminOperationDetectionCoversDestructiveAndJobStarts() {
        assertTrue(
            ApiSecurityPolicy.isAdminOperation(
                "POST",
                "/api/database/capital-month-backup"
            )
        )
        assertTrue(
            ApiSecurityPolicy.isAdminOperation(
                "POST",
                "/api/database/capital-month-migrate"
            )
        )
        assertTrue(
            ApiSecurityPolicy.isAdminOperation(
                "POST",
                "/api/history/equities/start"
            )
        )
        assertTrue(
            ApiSecurityPolicy.isAdminOperation(
                "POST",
                "/api/history/mcx/start"
            )
        )
        assertFalse(
            ApiSecurityPolicy.isAdminOperation(
                "GET",
                "/api/history/equities/status"
            )
        )
    }

    @Test
    fun serverNetworkLimitsAreBounded() {
        assertEquals("127.0.0.1", ApiSecurityPolicy.LOOPBACK_HOST)
        assertTrue(ApiSecurityPolicy.MAX_HEADER_COUNT in 1..128)
        assertTrue(ApiSecurityPolicy.MAX_BODY_BYTES <= 64 * 1024)
        assertTrue(ApiSecurityPolicy.SOCKET_TIMEOUT_MILLIS in 1..30_000)
        assertTrue(ApiSecurityPolicy.MAX_CONCURRENT_REQUESTS in 1..64)
        assertTrue(ApiSecurityPolicy.RATE_LIMIT_PER_MINUTE in 1..600)
        assertTrue(
            ApiSecurityPolicy.RATE_LIMIT_BURST in
                1..ApiSecurityPolicy.RATE_LIMIT_PER_MINUTE
        )
    }
}
