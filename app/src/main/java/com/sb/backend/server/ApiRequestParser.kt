package com.sb.backend.server

import java.io.BufferedInputStream
import java.net.URLDecoder
import java.util.Locale

class ApiHttpException(
    val statusCode: Int,
    val statusText: String,
    override val message: String
) : Exception(message)

data class ApiRequest(
    val method: String,
    val path: String,
    val queryParameters: Map<String, String>,
    val headers: Map<String, String>,
    val contentLength: Int
)

object ApiRequestParser {

    private const val MAX_PARAMETER_NAME = 128
    private const val MAX_PARAMETER_VALUE = 1024
    private val METHOD_PATTERN = Regex("[A-Z]{1,16}")
    private val HEADER_NAME_PATTERN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")
    private val SENSITIVE_HEADERS = setOf(
        "authorization",
        "content-length",
        "transfer-encoding",
        "x-sb-token",
        "x-sb-admin-token"
    )

    fun read(input: BufferedInputStream): ApiRequest {
        val requestLine = readLine(
            input = input,
            maxLength = ApiSecurityPolicy.MAX_REQUEST_LINE_BYTES,
            allowEof = true
        ) ?: throw badRequest("Empty request")

        val parts = requestLine.split(" ")
        if (parts.size != 3) throw badRequest("Malformed request line")

        val method = parts[0]
        val target = parts[1]
        val version = parts[2]

        if (!METHOD_PATTERN.matches(method)) {
            throw badRequest("Invalid HTTP method")
        }
        if (!version.startsWith("HTTP/1.")) {
            throw badRequest("Unsupported HTTP version")
        }
        if (!target.startsWith("/") || target.any { it.code < 0x20 }) {
            throw badRequest("Invalid request target")
        }

        val path = target.substringBefore("?")
        if (path.isEmpty() || path.length > ApiSecurityPolicy.MAX_QUERY_LENGTH) {
            throw badRequest("Invalid request path")
        }

        val query = target.substringAfter("?", "")
        val queryParameters = parseQuery(query)
        val headers = readHeaders(input)

        val contentLength = parseContentLength(headers)
        if (contentLength > ApiSecurityPolicy.MAX_BODY_BYTES) {
            throw ApiHttpException(
                413,
                "Payload Too Large",
                "Request body is too large"
            )
        }
        if (headers["transfer-encoding"] != null) {
            throw badRequest("Transfer-Encoding is not supported")
        }
        drainBody(input, contentLength)

        return ApiRequest(
            method = method,
            path = path,
            queryParameters = queryParameters,
            headers = headers,
            contentLength = contentLength
        )
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var count = 0

        while (true) {
            val line = readLine(
                input = input,
                maxLength = ApiSecurityPolicy.MAX_HEADER_LINE_BYTES,
                allowEof = false
            ) ?: throw badRequest("Unexpected end of headers")

            if (line.isEmpty()) break

            count++
            if (count > ApiSecurityPolicy.MAX_HEADER_COUNT) {
                throw ApiHttpException(
                    431,
                    "Request Header Fields Too Large",
                    "Too many request headers"
                )
            }

            val separator = line.indexOf(':')
            if (separator <= 0) throw badRequest("Malformed request header")

            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (!HEADER_NAME_PATTERN.matches(name)) {
                throw badRequest("Invalid request header name")
            }
            if (value.any { it == '\u0000' || it.code == 0x7F }) {
                throw badRequest("Invalid request header value")
            }

            val key = name.lowercase(Locale.US)
            if (key in SENSITIVE_HEADERS && headers.containsKey(key)) {
                throw badRequest("Duplicate security header")
            }
            headers[key] = value
        }

        return headers
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        if (query.length > ApiSecurityPolicy.MAX_QUERY_LENGTH) {
            throw badRequest("Query string is too large")
        }

        val result = LinkedHashMap<String, String>()
        val parts = query.split("&").filter { it.isNotBlank() }
        if (parts.size > ApiSecurityPolicy.MAX_QUERY_PARAMETERS) {
            throw badRequest("Too many query parameters")
        }

        parts.forEach { part ->
            val pair = part.split("=", limit = 2)
            val key = decode(pair[0])
            val value = decode(pair.getOrElse(1) { "" })
            if (key.isEmpty() ||
                key.length > MAX_PARAMETER_NAME ||
                value.length > MAX_PARAMETER_VALUE
            ) {
                throw badRequest("Invalid query parameter")
            }
            if (result.containsKey(key)) {
                throw badRequest("Duplicate query parameter")
            }
            result[key] = value
        }

        return result
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: IllegalArgumentException) {
            throw badRequest("Invalid query encoding")
        }
    }

    private fun parseContentLength(headers: Map<String, String>): Int {
        val raw = headers["content-length"] ?: return 0
        if (!raw.matches(Regex("\\d{1,9}"))) {
            throw badRequest("Invalid Content-Length")
        }
        return raw.toIntOrNull() ?: throw badRequest("Invalid Content-Length")
    }

    private fun drainBody(input: BufferedInputStream, length: Int) {
        if (length <= 0) return

        val buffer = ByteArray(4096)
        var remaining = length
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) throw badRequest("Incomplete request body")
            remaining -= read
        }
    }

    private fun readLine(
        input: BufferedInputStream,
        maxLength: Int,
        allowEof: Boolean
    ): String? {
        val result = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (result.isEmpty() && allowEof) return null
                if (result.isEmpty()) {
                    throw badRequest("Unexpected end of request")
                }
                return result.toString()
            }
            if (value == 10) break
            if (value != 13) {
                result.append(value.toChar())
                if (result.length > maxLength) {
                    throw ApiHttpException(
                        431,
                        "Request Header Fields Too Large",
                        "Request line or header is too large"
                    )
                }
            }
        }
        return result.toString()
    }

    private fun badRequest(message: String): ApiHttpException =
        ApiHttpException(400, "Bad Request", message)
}

class ApiRateLimiter(
    private val requestsPerMinute: Int =
        ApiSecurityPolicy.RATE_LIMIT_PER_MINUTE,
    private val burst: Int = ApiSecurityPolicy.RATE_LIMIT_BURST
) {

    private data class Bucket(
        var tokens: Double,
        var updatedAt: Long
    )

    private val buckets = HashMap<String, Bucket>()
    private val refillPerMillisecond =
        requestsPerMinute.toDouble() / 60_000.0

    @Synchronized
    fun acquire(
        key: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Long? {
        val bucket = buckets.getOrPut(key) {
            Bucket(burst.toDouble(), nowMillis)
        }
        val elapsed = (nowMillis - bucket.updatedAt).coerceAtLeast(0L)
        bucket.tokens = minOf(
            burst.toDouble(),
            bucket.tokens + elapsed * refillPerMillisecond
        )
        bucket.updatedAt = nowMillis

        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0
            return null
        }

        val missing = 1.0 - bucket.tokens
        val retryMillis = if (refillPerMillisecond <= 0.0) {
            60_000L
        } else {
            (missing / refillPerMillisecond).toLong() + 1L
        }
        return ((retryMillis + 999L) / 1000L).coerceAtLeast(1L)
    }

    @Synchronized
    fun prune(nowMillis: Long = System.currentTimeMillis()) {
        if (buckets.size < 1024) return
        buckets.entries.removeAll { (_, bucket) ->
            nowMillis - bucket.updatedAt > 10 * 60_000L
        }
    }
}
