package com.sb.backend.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream

class ApiRequestParserTest {

    @Test
    fun parsesGetRequestAndQuery() {
        val request = parse(
            "GET /api/database/candle-audit?symbol=INFY&exchange=NSE HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "X-SB-Token: read-token\r\n" +
                "\r\n"
        )

        assertEquals("GET", request.method)
        assertEquals("/api/database/candle-audit", request.path)
        assertEquals("INFY", request.queryParameters["symbol"])
        assertEquals("NSE", request.queryParameters["exchange"])
        assertEquals("read-token", request.headers["x-sb-token"])
    }

    @Test
    fun rejectsMissingOrMalformedRequestLine() {
        assertStatus(400) { parse("\r\n") }
        assertStatus(400) { parse("GET /api/health\r\n\r\n") }
        assertStatus(400) { parse("get /api/health HTTP/1.1\r\n\r\n") }
        assertStatus(400) { parse("GET relative HTTP/1.1\r\n\r\n") }
        assertStatus(400) { parse("GET /api/health HTTP/2.0\r\n\r\n") }
    }

    @Test
    fun rejectsDuplicateSecurityHeaders() {
        assertStatus(400) {
            parse(
                "GET /api/health HTTP/1.1\r\n" +
                    "X-SB-Token: one\r\n" +
                    "x-sb-token: two\r\n" +
                    "\r\n"
            )
        }
        assertStatus(400) {
            parse(
                "POST /api/health HTTP/1.1\r\n" +
                    "Content-Length: 0\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"
            )
        }
    }

    @Test
    fun rejectsOversizedOrUnsupportedBodies() {
        assertStatus(413) {
            parse(
                "POST /api/history/equities/start HTTP/1.1\r\n" +
                    "Content-Length: ${ApiSecurityPolicy.MAX_BODY_BYTES + 1}\r\n" +
                    "\r\n"
            )
        }
        assertStatus(400) {
            parse(
                "POST /api/history/equities/start HTTP/1.1\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n"
            )
        }
        assertStatus(400) {
            parse(
                "POST /api/history/equities/start HTTP/1.1\r\n" +
                    "Content-Length: 10\r\n" +
                    "\r\n" +
                    "short"
            )
        }
    }

    @Test
    fun rejectsDuplicateOrOversizedQueryParameters() {
        assertStatus(400) {
            parse(
                "GET /api/database/candle-audit?symbol=INFY&symbol=TCS HTTP/1.1\r\n" +
                    "\r\n"
            )
        }
        assertStatus(400) {
            parse(
                "GET /api/database/candle-audit?symbol=" +
                    "A".repeat(1025) +
                    " HTTP/1.1\r\n\r\n"
            )
        }
        assertStatus(400) {
            parse(
                "GET /api/database/candle-audit?symbol=%ZZ HTTP/1.1\r\n" +
                    "\r\n"
            )
        }
    }

    @Test
    fun parsesAuthorizationBearerToken() {
        val request = parse(
            "GET /api/health HTTP/1.1\r\n" +
                "Authorization: Bearer admin-token\r\n" +
                "\r\n"
        )

        assertEquals(
            "Bearer admin-token",
            request.headers["authorization"]
        )
    }

    @Test
    fun acceptsEmptyBodyAtTheConfiguredLimit() {
        val request = parse(
            "POST /api/history/mcx/start HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
        )

        assertEquals(0, request.contentLength)
    }

    private fun parse(raw: String): ApiRequest {
        return ApiRequestParser.read(
            BufferedInputStream(
                ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
            )
        )
    }

    private fun assertStatus(status: Int, block: () -> Unit) {
        val error = assertThrows(ApiHttpException::class.java) {
            block()
        }
        assertEquals(status, error.statusCode)
        assertTrue(error.message.isNotBlank())
    }
}
