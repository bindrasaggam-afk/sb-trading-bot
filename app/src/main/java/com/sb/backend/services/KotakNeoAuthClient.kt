
package com.sb.backend.services

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KotakNeoAuthClient {

    data class ViewSession(
        val token: String,
        val sid: String
    )

    data class TradeSession(
        val token: String,
        val sid: String,
        val baseUrl: String,
        val feedUrl: String
    )

    private fun post(
        endpoint: String,
        accessToken: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: JSONObject
    ): JSONObject {

        val connection = URL(endpoint).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true

            connection.setRequestProperty("Authorization", accessToken)
            connection.setRequestProperty("neo-fin-key", "neotradeapi")
            connection.setRequestProperty("Content-Type", "application/json")

            extraHeaders.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            connection.outputStream.use {
                it.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.bufferedReader()?.use {
                it.readText()
            } ?: ""

            val response = try {
                JSONObject(responseText)
            } catch (e: Exception) {
                throw Exception("Kotak API invalid response (HTTP $statusCode)")
            }

            if (statusCode !in 200..299) {
                throw Exception(
                    response.optString(
                        "message",
                        "Kotak API HTTP error: $statusCode"
                    )
                )
            }

            return response

        } finally {
            connection.disconnect()
        }
    }

    fun loginWithTotp(
        accessToken: String,
        mobile: String,
        ucc: String,
        totp: String
    ): ViewSession {

        require(accessToken.isNotBlank()) { "API Access Token missing" }
        require(mobile.isNotBlank()) { "Mobile number missing" }
        require(ucc.isNotBlank()) { "UCC missing" }
        require(totp.matches(Regex("\\d{6}"))) {
            "TOTP must be 6 digits"
        }

        val body = JSONObject().apply {
            put("mobileNumber", mobile)
            put("ucc", ucc)
            put("totp", totp)
        }

        val response = post(
            "https://mis.kotaksecurities.com/login/1.0/tradeApiLogin",
            accessToken,
            body = body
        )

        val data = response.optJSONObject("data")
            ?: throw Exception(
                response.optString("message", "TOTP Login failed")
            )

        val token = data.optString("token")
        val sid = data.optString("sid")

        if (token.isBlank() || sid.isBlank()) {
            throw Exception("View session not received")
        }

        return ViewSession(token, sid)
    }

    fun validateMpin(
        accessToken: String,
        viewSession: ViewSession,
        mpin: String
    ): TradeSession {

        require(accessToken.isNotBlank()) {
            "API Access Token missing"
        }

        require(mpin.matches(Regex("\\d{6}"))) {
            "MPIN must be 6 digits"
        }

        val body = JSONObject().apply {
            put("mpin", mpin)
        }

        val response = post(
            "https://mis.kotaksecurities.com/login/1.0/tradeApiValidate",
            accessToken,
            mapOf(
                "sid" to viewSession.sid,
                "Auth" to viewSession.token
            ),
            body
        )

        val data = response.optJSONObject("data")
            ?: throw Exception(
                response.optString("message", "MPIN validation failed")
            )

        val token = data.optString("token")
        val sid = data.optString("sid")
        val baseUrl = data.optString("baseUrl")
        var feedUrl = data.optString("feedUrl").trim()

        if (token.isBlank() || sid.isBlank() || baseUrl.isBlank()) {
            throw Exception("Trade session incomplete")
        }

        if (feedUrl.isBlank()) {
            throw Exception("Trade login successful, but feedUrl was not returned. WebSocket cannot connect yet.")
        } else {
            feedUrl = when {
                feedUrl.startsWith("https://", ignoreCase = true) ->
                    "wss://" + feedUrl.substring(8)

                feedUrl.startsWith("http://", ignoreCase = true) ->
                    "wss://" + feedUrl.substring(7)

                feedUrl.startsWith("wss://", ignoreCase = true) ->
                    feedUrl

                feedUrl.startsWith("ws://", ignoreCase = true) ->
                    "wss://" + feedUrl.substring(5)

                else -> throw Exception("Invalid Kotak feed URL scheme")
            }
        }

        return TradeSession(
            token = token,
            sid = sid,
            baseUrl = baseUrl,
            feedUrl = feedUrl
        )
    }
}
