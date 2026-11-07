package com.sb.ui.chart

import android.widget.TextView
import com.sb.backend.market.model.DepthLevel
import com.sb.backend.market.model.MarketTick

class MarketDepthAdapter(
    private val bidPriceViews: List<TextView>,
    private val bidQuantityViews: List<TextView>,
    private val askPriceViews: List<TextView>,
    private val askQuantityViews: List<TextView>,
    private val priceFormatter: (Double?) -> String,
    private val quantityFormatter: (Long) -> String,
    private val bidColor: Int,
    private val askColor: Int,
    private val quantityColor: Int,
    private val emptyColor: Int
) {

    fun bind(tick: MarketTick?) {
        if (tick == null) {
            bindSide(bidPriceViews, bidQuantityViews, emptyList(), bidColor)
            bindSide(askPriceViews, askQuantityViews, emptyList(), askColor)
            return
        }

        val bids = normalizedLevels(
            levels = tick.bids,
            fallbackPrice = tick.bidPrice,
            fallbackQuantity = tick.bidQty,
            descending = true
        )
        val asks = normalizedLevels(
            levels = tick.asks,
            fallbackPrice = tick.askPrice,
            fallbackQuantity = tick.askQty,
            descending = false
        )

        bindSide(bidPriceViews, bidQuantityViews, bids, bidColor)
        bindSide(askPriceViews, askQuantityViews, asks, askColor)
    }

    private fun normalizedLevels(
        levels: List<DepthLevel>,
        fallbackPrice: Double?,
        fallbackQuantity: Long?,
        descending: Boolean
    ): List<DepthLevel> {
        val valid = levels.filter { it.price.isFinite() && it.price > 0.0 }
        val sorted = if (descending) {
            valid.sortedByDescending { it.price }
        } else {
            valid.sortedBy { it.price }
        }

        if (sorted.isNotEmpty()) return sorted

        val price = fallbackPrice ?: return emptyList()
        if (!price.isFinite() || price <= 0.0) return emptyList()
        return listOf(DepthLevel(price = price, quantity = fallbackQuantity ?: 0L))
    }

    private fun bindSide(
        priceViews: List<TextView>,
        quantityViews: List<TextView>,
        levels: List<DepthLevel>,
        priceColor: Int
    ) {
        for (index in priceViews.indices) {
            val level = levels.getOrNull(index)
            if (level == null) {
                priceViews[index].text = priceFormatter(null)
                priceViews[index].setTextColor(emptyColor)
            } else {
                priceViews[index].text = priceFormatter(level.price)
                priceViews[index].setTextColor(priceColor)
            }

            val quantityView = quantityViews.getOrNull(index) ?: continue
            if (level == null || level.quantity <= 0L) {
                quantityView.text = "—"
                quantityView.setTextColor(emptyColor)
            } else {
                quantityView.text = quantityFormatter(level.quantity)
                quantityView.setTextColor(quantityColor)
            }
        }
    }
}
