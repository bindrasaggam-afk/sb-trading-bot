package com.sb.backend.server

enum class ApiAccess {
    PUBLIC,
    READ,
    ADMIN
}

enum class ApiOperation {
    HEALTH,
    READ_ONLY,
    BACKUP,
    DESTRUCTIVE,
    NETWORK_JOB,
    META
}

data class ApiEndpoint(
    val method: String,
    val path: String,
    val access: ApiAccess,
    val operation: ApiOperation,
    val description: String
)

object ApiSecurityPolicy {

    const val LOOPBACK_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 10000
    const val MAX_REQUEST_LINE_BYTES = 8 * 1024
    const val MAX_HEADER_LINE_BYTES = 8 * 1024
    const val MAX_HEADER_COUNT = 64
    const val MAX_QUERY_LENGTH = 2 * 1024
    const val MAX_QUERY_PARAMETERS = 64
    const val MAX_BODY_BYTES = 64 * 1024
    const val SOCKET_TIMEOUT_MILLIS = 10_000
    const val MAX_CONCURRENT_REQUESTS = 16
    const val RATE_LIMIT_PER_MINUTE = 300
    const val RATE_LIMIT_BURST = 60

    val endpoints: List<ApiEndpoint> = listOf(
        ApiEndpoint(
            "GET",
            "/api/health",
            ApiAccess.READ,
            ApiOperation.HEALTH,
            "Backend liveness"
        ),
        ApiEndpoint(
            "GET",
            "/api/broker/status",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Broker connection and authentication state"
        ),
        ApiEndpoint(
            "GET",
            "/api/system/status",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Local application status"
        ),
        ApiEndpoint(
            "GET",
            "/api/database/diagnostics",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Database schema, counts and latest data"
        ),
        ApiEndpoint(
            "GET",
            "/api/database/candle-audit",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Candle integrity audit"
        ),
        ApiEndpoint(
            "GET",
            "/api/database/capital-month-audit",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Uppercase 1M classification audit"
        ),
        ApiEndpoint(
            "GET",
            "/api/database/integrity-audit",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Database integrity audit"
        ),
        ApiEndpoint(
            "GET",
            "/api/history/validation",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Historical data validation"
        ),
        ApiEndpoint(
            "POST",
            "/api/database/capital-month-backup",
            ApiAccess.ADMIN,
            ApiOperation.BACKUP,
            "Create uppercase 1M recovery backup"
        ),
        ApiEndpoint(
            "POST",
            "/api/database/capital-month-migrate",
            ApiAccess.ADMIN,
            ApiOperation.DESTRUCTIVE,
            "Recover and delete verified uppercase 1M rows"
        ),
        ApiEndpoint(
            "POST",
            "/api/history/equities/start",
            ApiAccess.ADMIN,
            ApiOperation.NETWORK_JOB,
            "Start equity historical backfill"
        ),
        ApiEndpoint(
            "GET",
            "/api/history/equities/status",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "Equity historical backfill status"
        ),
        ApiEndpoint(
            "POST",
            "/api/history/mcx/start",
            ApiAccess.ADMIN,
            ApiOperation.NETWORK_JOB,
            "Start MCX historical backfill"
        ),
        ApiEndpoint(
            "GET",
            "/api/history/mcx/status",
            ApiAccess.READ,
            ApiOperation.READ_ONLY,
            "MCX historical backfill status"
        )
    )

    fun endpoint(method: String, path: String): ApiEndpoint? {
        return endpoints.firstOrNull {
            it.method == method && it.path == path
        }
    }

    fun pathExists(path: String): Boolean =
        endpoints.any { it.path == path }

    fun allowedMethods(path: String): List<String> =
        endpoints.filter { it.path == path }
            .map { it.method }
            .distinct()
            .sorted()

    fun statusCodeFor(method: String, path: String): Int {
        if (method == "OPTIONS") return 200
        if (endpoint(method, path) != null) return 200
        return if (pathExists(path)) 405 else 404
    }

    fun isAdminOperation(method: String, path: String): Boolean =
        endpoint(method, path)?.access == ApiAccess.ADMIN

    fun requiresAuthentication(method: String): Boolean =
        method != "OPTIONS"
}
