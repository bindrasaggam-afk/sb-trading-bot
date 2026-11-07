package com.sb.backend.server

import com.sb.backend.services.KotakSessionCoordinator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EmbeddedApiServer(
    private val port: Int = 10000
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    fun start(): Boolean {
        if (running.get()) return true

        return try {
            val socket = ServerSocket(
                port,
                50,
                InetAddress.getByName("127.0.0.1")
            )

            serverSocket = socket
            running.set(true)

            executor.execute {
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        executor.execute {
                            handleClient(client)
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            e.printStackTrace()
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            running.set(false)
            e.printStackTrace()
            false
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { connection ->
                val input = BufferedInputStream(connection.getInputStream())
                val output = BufferedOutputStream(connection.getOutputStream())

                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")

                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: "/"

                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                }

                val authenticated =
                    KotakSessionCoordinator.isFeedAuthenticated()

                val connected =
                    KotakSessionCoordinator.isFeedConnected()

                val brokerStatus = when {
                    authenticated -> "authenticated"
                    connected -> "connected"
                    else -> "disconnected"
                }

                val brokerMessage = jsonEscape(
                    KotakSessionCoordinator.currentStatus()
                )

                val response = when {
                    method == "GET" && path == "/api/health" ->
                        """{"status":"ok","service":"SB Bot Android Backend","server":"running"}"""

                    method == "GET" && path == "/api/broker/status" ->
                        """
                        {
                            "status": "$brokerStatus",
                            "broker": "Kotak Neo",
                            "connected": $connected,
                            "authenticated": $authenticated,
                            "message": "$brokerMessage"
                        }
                        """.trimIndent()

                    method == "GET" && path == "/api/system/status" ->
                        """{"app":"SB Bot","backend":"running","mode":"LOCAL_ANDROID"}"""

                    method == "OPTIONS" ->
                        """{"status":"ok"}"""

                    else ->
                        """{"status":"error","message":"Endpoint not found"}"""
                }

                val status = if (
                    path == "/api/health" ||
                    path == "/api/broker/status" ||
                    path == "/api/system/status" ||
                    method == "OPTIONS"
                ) "200 OK" else "404 Not Found"

                val body = response.toByteArray(Charsets.UTF_8)

                val headers = (
                    "HTTP/1.1 $status\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                    "\r\n"
                )

                output.write(headers.toByteArray(Charsets.UTF_8))
                output.write(body)
                output.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun jsonEscape(value: String): String {
        val builder = StringBuilder(value.length + 16)

        value.forEach { character ->
            when (character) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (character < ' ') {
                        builder.append("\\u")
                        builder.append(
                            character.code
                                .toString(16)
                                .padStart(4, '0')
                        )
                    } else {
                        builder.append(character)
                    }
                }
            }
        }

        return builder.toString()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val result = StringBuilder()

        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (result.isEmpty()) null else result.toString()
            }
            if (value == 10) break
            if (value != 13) result.append(value.toChar())
        }

        return result.toString()
    }

    fun isRunning(): Boolean {
        return running.get() && serverSocket?.isBound == true
    }

    fun stop() {
        running.set(false)

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null
    }
}
