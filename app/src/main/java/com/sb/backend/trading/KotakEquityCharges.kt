package com.sb.backend.trading

/**
 * Kotak Securities equity-cash paper charge model.
 *
 * This profile models the post-promotional Kotak Neo Trade Free Plan tariffs:
 * delivery brokerage 0.20% and intraday brokerage 0.05% of turnover capped
 * at ₹10 per order. The first-30-days promotional waiver is not modelled because
 * account tenure is unknown. Broker plans can change; this is not a contract-note
 * guarantee. Exchange/statutory rates are separately versioned below.
 */
data class PaperChargeBreakdown(
    val brokerage: Double,
    val stt: Double,
    val exchangeTransaction: Double,
    val sebiTurnover: Double,
    val gst: Double,
    val stampDuty: Double,
    val dpCharge: Double = 0.0
) {
    val total: Double
        get() = brokerage + stt + exchangeTransaction +
            sebiTurnover + gst + stampDuty + dpCharge
}

object KotakEquityCharges {
    const val PROFILE_ID = "KOTAK_TRADE_FREE_POST_PROMO_2026_11"
    const val BROKERAGE_DELIVERY_RATE = 0.002
    const val BROKERAGE_INTRADAY_RATE = 0.0005
    const val BROKERAGE_INTRADAY_MAXIMUM = 10.0
    const val NSE_TRANSACTION_RATE = 307.0 / 10_000_000.0
    const val BSE_TRANSACTION_RATE = 375.0 / 10_000_000.0
    const val SEBI_TURNOVER_RATE = 10.0 / 10_000_000.0
    const val GST_RATE = 0.18
    const val STT_DELIVERY_RATE = 0.001
    const val STT_INTRADAY_SELL_RATE = 0.00025
    const val STAMP_DELIVERY_BUY_RATE = 0.00015
    const val STAMP_INTRADAY_BUY_RATE = 0.00003
    const val DP_DELIVERY_SELL_RATE = 0.0004
    const val DP_DELIVERY_SELL_MINIMUM = 20.0

    fun calculate(
        side: String,
        category: String,
        price: Double,
        quantity: Int,
        exchange: String = "NSE",
        isExit: Boolean = false
    ): PaperChargeBreakdown {
        if (!price.isFinite() || price <= 0.0 || quantity <= 0) {
            return PaperChargeBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val exchangeRate = when (exchange.trim().uppercase()) {
            "NSE" -> NSE_TRANSACTION_RATE
            // BSE published standard A/B/other non-exclusive cash rate.
            // Exclusive scrips can carry a lower rate; this is conservative.
            "BSE" -> BSE_TRANSACTION_RATE
            else -> throw IllegalArgumentException(
                "Kotak equity charge profile supports NSE/BSE cash equities only."
            )
        }
        val normalizedSide = side.trim().uppercase()
        require(normalizedSide == "BUY" || normalizedSide == "SELL") {
            "Side must be BUY or SELL."
        }
        val intraday = category.trim().equals("INTRADAY", true)
        val turnover = price * quantity
        val brokerage = if (intraday) {
            minOf(BROKERAGE_INTRADAY_MAXIMUM, turnover * BROKERAGE_INTRADAY_RATE)
        } else {
            turnover * BROKERAGE_DELIVERY_RATE
        }
        val stt = if (intraday) {
            if (normalizedSide == "SELL") turnover * STT_INTRADAY_SELL_RATE else 0.0
        } else {
            turnover * STT_DELIVERY_RATE
        }
        val exchangeCharge = turnover * exchangeRate
        val sebi = turnover * SEBI_TURNOVER_RATE
        val dpCharge = if (
            isExit && !intraday && normalizedSide == "SELL"
        ) {
            maxOf(DP_DELIVERY_SELL_MINIMUM, turnover * DP_DELIVERY_SELL_RATE)
        } else {
            0.0
        }
        val gst = (brokerage + exchangeCharge + sebi + dpCharge) * GST_RATE
        val stamp = if (normalizedSide == "BUY") {
            turnover * if (intraday) STAMP_INTRADAY_BUY_RATE
            else STAMP_DELIVERY_BUY_RATE
        } else {
            0.0
        }
        return PaperChargeBreakdown(
            brokerage = brokerage,
            stt = stt,
            exchangeTransaction = exchangeCharge,
            sebiTurnover = sebi,
            gst = gst,
            stampDuty = stamp,
            dpCharge = dpCharge
        )
    }
}
