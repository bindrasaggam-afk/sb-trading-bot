package com.sb.backend.services

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class KotakNeoBinaryParserTest {

    @Test
    fun miniPacketScalesPreviousClose() {
        val packet = miniPacket(
            exchangeId = 1,
            ltpRaw = 12_345_00,
            closeRaw = 12_340_00,
            changeRaw = 5_00
        )

        val tick = KotakNeoBinaryParser().parsePacket(packet)

        assertNotNull(tick)
        assertEquals(12_345.0, tick!!.ltp, 0.0001)
        assertEquals(12_340.0, tick.close, 0.0001)
        assertEquals(5.0, tick.change, 0.0001)
    }

    @Test
    fun miniPacketKeepsNegativeChangeSigned() {
        val packet = miniPacket(
            exchangeId = 1,
            ltpRaw = 12_340_00,
            closeRaw = 12_345_00,
            changeRaw = -5_00
        )

        val tick = KotakNeoBinaryParser().parsePacket(packet)

        assertNotNull(tick)
        assertEquals(-5.0, tick!!.change, 0.0001)
    }

    private fun miniPacket(
        exchangeId: Int,
        ltpRaw: Int,
        closeRaw: Int,
        changeRaw: Int
    ): ByteArray {
        val buffer = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0, 54.toShort())
        buffer.putShort(2, 7208.toShort())
        buffer.put(4, exchangeId.toByte())
        buffer.put(5, 1.toByte())
        buffer.putLong(9, 12_345L)
        buffer.putLong(13, 1_700_000_000_000L)
        buffer.putInt(21, ltpRaw)
        buffer.putLong(25, 10L)
        buffer.putInt(33, closeRaw)
        buffer.putInt(37, 12)
        buffer.putInt(41, changeRaw)
        buffer.putInt(45, 1)
        buffer.put(49, 2.toByte())
        return buffer.array()
    }
}
