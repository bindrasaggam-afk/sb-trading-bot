
package com.sb.backend.services

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class KotakDepthRow(
    val quantity: Long,
    val price: Double,
    val orders: Int
)

data class KotakDecodedTick(
    val messageCode: Int,
    val exchangeId: Int,
    val exchange: String,
    val level: Int,
    val token: Long,
    val symbol: String = "",
    val isIndex: Boolean = false,
    val ltp: Double = 0.0,
    val open: Double = 0.0,
    val high: Double = 0.0,
    val low: Double = 0.0,
    val close: Double = 0.0,
    val change: Double = 0.0,
    val changePercent: Double = 0.0,
    val volume: Long = 0L,
    val lastTradeQuantity: Long = 0L,
    val lastTradeTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
    val averageTradePrice: Double = 0.0,
    val openInterest: Long = 0L,
    val totalBuyQuantity: Long = 0L,
    val totalSellQuantity: Long = 0L,
    val upperCircuit: Double = 0.0,
    val lowerCircuit: Double = 0.0,
    val yearlyHigh: Double = 0.0,
    val yearlyLow: Double = 0.0,
    val precision: Int = 2,
    val marketLot: Long = 0L,
    val depth: List<KotakDepthRow> = emptyList(),
    val buyDepth: List<KotakDepthRow> = emptyList(),
    val sellDepth: List<KotakDepthRow> = emptyList(),
    val statusCode: Int = 0,
    val status: String = ""
)

class KotakNeoBinaryParser {

    companion object {
        private const val HEADER_SIZE = 9
        private const val CODE_INDEX = 7207
        private const val CODE_MARKET_PICTURE = 7208
        private const val CODE_MARKET_STATUS = 105
        private const val CODE_CAS = 104
    }

    private val exchangeNames = mapOf(
        0 to "none",
        1 to "nse_cm",
        2 to "nse_fo",
        3 to "cde_fo",
        4 to "nse_com",
        5 to "bse_cm",
        6 to "bse_fo",
        7 to "bse_cd",
        8 to "bse_co",
        9 to "mcx_fo",
        10 to "ncd_co"
    )

    @Volatile
    private var dividers: Map<Int, Double> = emptyMap()

    fun setDividers(values: Map<Int, Double>) {
        dividers = values.filterValues {
            it.isFinite() && it > 0.0
        }
    }

    private fun divider(exchangeId: Int): Double {
        return dividers[exchangeId] ?: when (exchangeId) {
            3, 7 -> 10_000_000.0
            4, 8 -> 10_000.0
            10 -> 1.0
            else -> 100.0
        }
    }

    private fun buffer(bytes: ByteArray): ByteBuffer {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readUnsignedInt(
        b: ByteBuffer,
        offset: Int
    ): Long {
        return b.getInt(offset).toLong() and 0xFFFFFFFFL
    }

    private fun readString(
        bytes: ByteArray,
        offset: Int,
        length: Int
    ): String {
        if (
            offset < 0 ||
            length <= 0 ||
            offset >= bytes.size
        ) {
            return ""
        }

        val end = minOf(
            bytes.size.toLong(),
            offset.toLong() + length.toLong()
        ).toInt()

        var actualEnd = offset

        while (
            actualEnd < end &&
            bytes[actualEnd].toInt() != 0
        ) {
            actualEnd++
        }

        return String(
            bytes,
            offset,
            actualEnd - offset,
            Charsets.UTF_8
        ).trim()
    }

    fun parseFrame(
        frame: ByteArray
    ): List<KotakDecodedTick> {

        val result = mutableListOf<KotakDecodedTick>()
        var offset = 0

        while (offset <= frame.size - 2) {

            val length = (
                (frame[offset].toInt() and 0xFF) or
                    (
                        (frame[offset + 1].toInt() and 0xFF)
                            shl 8
                    )
                )

            if (
                length < HEADER_SIZE ||
                length > frame.size - offset
            ) {
                break
            }

            val packet = frame.copyOfRange(
                offset,
                offset + length
            )

            try {
                parsePacket(packet)?.let {
                    result.add(it)
                }
            } catch (_: Exception) {
                // Ignore malformed packet.
            }

            offset += length
        }

        return result
    }

    fun parsePacket(
        packet: ByteArray
    ): KotakDecodedTick? {

        if (packet.size < HEADER_SIZE) return null

        val b = buffer(packet)

        val declaredLength =
            b.getShort(0).toInt() and 0xFFFF

        if (declaredLength != packet.size) return null

        val messageCode =
            b.getShort(2).toInt() and 0xFFFF

        val exchangeId =
            b.get(4).toInt() and 0xFF

        val level =
            b.get(5).toInt() and 0xFF

        val exchange =
            exchangeNames[exchangeId] ?: return null

        val scale = divider(exchangeId)

        return when {

            messageCode == CODE_INDEX ->
                parseIndex(
                    packet,
                    exchangeId,
                    exchange,
                    scale
                )

            messageCode == CODE_MARKET_STATUS ->
                parseMarketStatus(
                    packet,
                    exchangeId,
                    exchange
                )

            messageCode == CODE_CAS ->
                parseCas(
                    packet,
                    exchangeId,
                    exchange,
                    scale
                )

            messageCode == CODE_MARKET_PICTURE &&
                level == 1 ->
                parseMini(
                    packet,
                    exchangeId,
                    exchange,
                    scale
                )

            messageCode == CODE_MARKET_PICTURE &&
                level in setOf(2, 4, 8) ->
                parseMarketPicture(
                    packet,
                    exchangeId,
                    exchange,
                    level,
                    scale
                )

            else -> null
        }
    }

    private fun parseIndex(
        packet: ByteArray,
        exchangeId: Int,
        exchange: String,
        scale: Double
    ): KotakDecodedTick? {

        if (packet.size < 87) return null

        val b = buffer(packet)

        val open = b.getInt(13) / scale
        val close = b.getInt(17) / scale
        val high = b.getInt(21) / scale
        val low = b.getInt(25) / scale
        val ltp = b.getInt(29) / scale

        val percent = b.getInt(49) / 100.0

        val precision =
            b.get(61).toInt() and 0xFF

        val name = readString(packet, 66, 21)

        return KotakDecodedTick(
            messageCode = CODE_INDEX,
            exchangeId = exchangeId,
            exchange = exchange,
            level = 0,
            token = readUnsignedInt(b, 9),
            symbol = name,
            isIndex = true,
            ltp = ltp,
            open = open,
            high = high,
            low = low,
            close = close,
            change = ltp - close,
            changePercent = percent,
            lastTradeTime = b.getLong(33),
            yearlyHigh = b.getInt(41) / scale,
            yearlyLow = b.getInt(45) / scale,
            precision = precision
        )
    }

    private fun parseMini(
        packet: ByteArray,
        exchangeId: Int,
        exchange: String,
        scale: Double
    ): KotakDecodedTick? {

        if (packet.size < 54) return null

        val b = buffer(packet)

        return KotakDecodedTick(
            messageCode = CODE_MARKET_PICTURE,
            exchangeId = exchangeId,
            exchange = exchange,
            level = 1,
            token = readUnsignedInt(b, 9),
            lastTradeTime = b.getLong(13),
            ltp = readUnsignedInt(b, 21) / scale,
            lastTradeQuantity = b.getLong(25),
            close = readUnsignedInt(b, 33) / scale,
            changePercent = b.getInt(37) / 100.0,
            change = b.getInt(41) / scale,
            marketLot = readUnsignedInt(b, 45),
            precision = b.get(49).toInt() and 0xFF
        )
    }

    private fun parseMarketPicture(
        packet: ByteArray,
        exchangeId: Int,
        exchange: String,
        level: Int,
        scale: Double
    ): KotakDecodedTick? {

        if (packet.size < 144) return null

        val b = buffer(packet)

        val buyCountRaw =
            readUnsignedInt(b, 89).coerceAtMost(100L)

        val sellCountRaw =
            readUnsignedInt(b, 93).coerceAtMost(100L)

        val buyCount =
            if (level == 4) 1 else buyCountRaw.toInt()

        val sellCount =
            if (level == 4) 1 else sellCountRaw.toInt()

        val rows = mutableListOf<KotakDepthRow>()

        var rowOffset = 144

        val availableRows =
            (packet.size - rowOffset) / 16

        val requestedRows =
            (buyCount + sellCount).coerceAtMost(availableRows)

        repeat(requestedRows) {

            val qty = b.getLong(rowOffset)

            val price =
                b.getInt(rowOffset + 8) / scale

            val orders =
                b.getInt(rowOffset + 12)

            rows.add(
                KotakDepthRow(
                    quantity = qty,
                    price = price,
                    orders = orders
                )
            )

            rowOffset += 16
        }

        val actualBuyCount =
            minOf(buyCount, rows.size)

        val buys = rows.take(actualBuyCount)
        val sells = rows.drop(actualBuyCount)

        return KotakDecodedTick(
            messageCode = CODE_MARKET_PICTURE,
            exchangeId = exchangeId,
            exchange = exchange,
            level = level,
            token = readUnsignedInt(b, 9),

            totalBuyQuantity = b.getLong(13),
            totalSellQuantity = b.getLong(21),
            volume = b.getLong(29),

            lastTradeTime = b.getLong(37),
            lastUpdateTime = b.getLong(45),

            open = readUnsignedInt(b, 53) / scale,
            close = readUnsignedInt(b, 57) / scale,
            high = readUnsignedInt(b, 61) / scale,
            low = readUnsignedInt(b, 65) / scale,
            ltp = readUnsignedInt(b, 69) / scale,

            lastTradeQuantity = b.getLong(73),

            averageTradePrice =
                readUnsignedInt(b, 81) / scale,

            changePercent = b.getInt(99) / 100.0,

            openInterest = readUnsignedInt(b, 103),

            change = b.getInt(115) / scale,

            upperCircuit =
                readUnsignedInt(b, 119) / scale,

            lowerCircuit =
                readUnsignedInt(b, 123) / scale,

            yearlyHigh =
                readUnsignedInt(b, 127) / scale,

            yearlyLow =
                readUnsignedInt(b, 131) / scale,

            marketLot = readUnsignedInt(b, 135),

            precision =
                b.get(139).toInt() and 0xFF,

            depth = buys + sells,
            buyDepth = buys,
            sellDepth = sells
        )
    }

    private fun parseMarketStatus(
        packet: ByteArray,
        exchangeId: Int,
        exchange: String
    ): KotakDecodedTick? {

        if (packet.size < 16) return null

        val b = buffer(packet)

        return KotakDecodedTick(
            messageCode = CODE_MARKET_STATUS,
            exchangeId = exchangeId,
            exchange = exchange,
            level = b.get(5).toInt() and 0xFF,
            token = 0L,
            statusCode =
                b.getShort(9).toInt() and 0xFFFF,
            status = readString(packet, 11, 5)
        )
    }

    private fun parseCas(
        packet: ByteArray,
        exchangeId: Int,
        exchange: String,
        scale: Double
    ): KotakDecodedTick? {

        if (packet.size < 48) return null

        val b = buffer(packet)

        return KotakDecodedTick(
            messageCode = CODE_CAS,
            exchangeId = exchangeId,
            exchange = exchange,
            level = b.get(5).toInt() and 0xFF,
            token = readUnsignedInt(b, 9),
            close = b.getInt(13) / scale,
            volume = b.getLong(17)
        )
    }
}
