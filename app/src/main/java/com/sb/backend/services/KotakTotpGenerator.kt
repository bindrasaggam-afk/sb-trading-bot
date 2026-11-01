package com.sb.backend.services

import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object KotakTotpGenerator {

    private const val PERIOD_SECONDS = 30L
    private const val CODE_DIGITS = 6

    fun generate(base32Secret: String): String {
        require(base32Secret.isNotBlank()) {
            "TOTP secret is missing"
        }

        val secret = decodeBase32(base32Secret)
        val counter = System.currentTimeMillis() / 1000L / PERIOD_SECONDS

        val counterBytes = ByteBuffer.allocate(8)
            .putLong(counter)
            .array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))

        val hash = mac.doFinal(counterBytes)
        val offset = hash.last().toInt() and 0x0F

        val binary =
            ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        val otp = binary % 1_000_000

        return otp.toString().padStart(CODE_DIGITS, '0')
    }

    fun secondsRemaining(): Int {
        val elapsed = System.currentTimeMillis() / 1000L
        return (PERIOD_SECONDS - elapsed % PERIOD_SECONDS).toInt()
    }

    private fun decodeBase32(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val clean = input
            .toUpperCase(Locale.ROOT)
            .replace(" ", "")
            .replace("-", "")
            .replace("=", "")

        require(clean.isNotEmpty()) { "Invalid TOTP secret" }

        val output = ArrayList<Byte>()
        var buffer = 0
        var bitsLeft = 0

        for (char in clean) {
            val value = alphabet.indexOf(char)
            require(value >= 0) { "Invalid Base32 TOTP secret" }

            buffer = (buffer shl 5) or value
            bitsLeft += 5

            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }

        return output.toByteArray()
    }
}
