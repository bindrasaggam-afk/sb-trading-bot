package com.sb.ui.market

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.repo.MarketFeedRepository
import com.sb.ui.theme.AppDesign
import java.util.Locale
import kotlin.math.abs

data class MarketListItem(
    val key: InstrumentKey,
    val title: String,
    val subtitle: String = ""
)

class MarketListAdapter(
    private val repository: MarketFeedRepository = MarketFeedRepository
) : RecyclerView.Adapter<MarketListAdapter.MarketRowViewHolder>() {

    private val titleColor = AppDesign.textPrimary
    private val mutedColor = AppDesign.textMuted
    private val upColor = AppDesign.positive
    private val downColor = AppDesign.negative
    private val upFlashColor = Color.argb(210, 16, 66, 38)
    private val downFlashColor = Color.argb(210, 78, 24, 24)

    private val items = ArrayList<MarketListItem>()

    private data class ActiveSubscription(
        val key: InstrumentKey,
        var count: Int
    )

    private val subscriptions = HashMap<String, ActiveSubscription>()
    private val visibleHolders = HashMap<String, MutableList<MarketRowViewHolder>>()

    var onItemClick: ((MarketListItem) -> Unit)? = null

    fun submitItems(newItems: List<MarketListItem>) {
        release()
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun onTick(tick: MarketTick) {
        if (tick.ltp <= 0.0 || !tick.ltp.isFinite()) return
        visibleHolders[tick.key.key()]?.forEach { holder ->
            holder.render(tick)
        }
    }

    fun refreshFromSnapshot() {
        visibleHolders.values.flatten().forEach { holder ->
            val item = holder.boundItem ?: return@forEach
            holder.render(MarketDataManager.getTick(item.key))
        }
    }

    fun release() {
        visibleHolders.values.flatten().forEach { holder ->
            holder.subscribedKey = null
            holder.subscribedInstrument = null
        }
        visibleHolders.clear()

        subscriptions.values.forEach { subscription ->
            repeat(subscription.count) {
                repository.unsubscribe(subscription.key)
            }
        }
        subscriptions.clear()
    }

    fun resubscribeVisible() {
        subscriptions.values.forEach { subscription ->
            repository.requestSubscription(subscription.key)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): MarketRowViewHolder {
        val context = parent.context
        val density = parent.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppDesign.cardBackground(context, 10)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }

        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val title = TextView(context).apply {
            textSize = 14f
            setTextColor(titleColor)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }

        val subtitle = TextView(context).apply {
            textSize = 10f
            setTextColor(mutedColor)
            maxLines = 1
        }

        textBlock.addView(title)
        textBlock.addView(subtitle)

        val valueBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        }

        val price = TextView(context).apply {
            textSize = 14f
            setTextColor(mutedColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            text = "—"
        }

        val change = TextView(context).apply {
            textSize = 10f
            setTextColor(mutedColor)
            gravity = Gravity.END
            text = "—"
        }

        valueBlock.addView(price)
        valueBlock.addView(change)

        row.addView(textBlock)
        row.addView(valueBlock)

        return MarketRowViewHolder(row, title, subtitle, price, change)
    }

    override fun onBindViewHolder(holder: MarketRowViewHolder, position: Int) {
        if (position !in items.indices) return

        val item = items[position]
        holder.bind(item, MarketDataManager.getTick(item.key))
        holder.itemView.setOnClickListener {
            val adapterPosition = holder.adapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) {
                items.getOrNull(adapterPosition)?.let { onItemClick?.invoke(it) }
            }
        }

        if (holder.itemView.isAttachedToWindow) {
            attachHolder(holder)
        }
    }

    override fun onViewAttachedToWindow(holder: MarketRowViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachHolder(holder)
    }

    override fun onViewDetachedFromWindow(holder: MarketRowViewHolder) {
        detachHolder(holder)
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: MarketRowViewHolder) {
        detachHolder(holder)
        super.onViewRecycled(holder)
    }

    private fun attachHolder(holder: MarketRowViewHolder) {
        val item = holder.boundItem ?: return
        val keyString = item.key.key()

        if (holder.subscribedKey == keyString) {
            val holders = visibleHolders.getOrPut(keyString) { mutableListOf() }
            if (!holders.contains(holder)) holders.add(holder)
            return
        }

        detachHolder(holder)

        holder.subscribedKey = keyString
        holder.subscribedInstrument = item.key

        val active = subscriptions[keyString]
        if (active == null) {
            subscriptions[keyString] = ActiveSubscription(item.key, 1)
            repository.subscribe(item.key)
        } else {
            active.count++
        }

        visibleHolders.getOrPut(keyString) { mutableListOf() }.add(holder)
    }

    private fun detachHolder(holder: MarketRowViewHolder) {
        val keyString = holder.subscribedKey ?: return
        holder.subscribedKey = null

        val instrument = holder.subscribedInstrument
        holder.subscribedInstrument = null

        visibleHolders[keyString]?.let { holders ->
            holders.remove(holder)
            if (holders.isEmpty()) visibleHolders.remove(keyString)
        }

        val active = subscriptions[keyString] ?: return
        active.count--
        if (active.count <= 0) {
            subscriptions.remove(keyString)
            repository.unsubscribe(instrument ?: active.key)
        }
    }

    inner class MarketRowViewHolder(
        itemView: View,
        private val titleView: TextView,
        private val subtitleView: TextView,
        private val priceView: TextView,
        private val changeView: TextView
    ) : RecyclerView.ViewHolder(itemView) {

        var boundItem: MarketListItem? = null
        var subscribedKey: String? = null
        var subscribedInstrument: InstrumentKey? = null

        private var lastLtp = 0.0
        private var flashToken = 0

        fun bind(item: MarketListItem, tick: MarketTick?) {
            if (boundItem?.key?.key() != item.key.key()) {
                lastLtp = 0.0
                flashToken++
                itemView.background = AppDesign.cardBackground(itemView.context, 10)
            }
            boundItem = item
            titleView.text = item.title
            subtitleView.text = item.subtitle
            subtitleView.visibility =
                if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
            render(tick)
        }

        fun render(tick: MarketTick?) {
            if (tick == null || tick.ltp <= 0.0 || !tick.ltp.isFinite()) {
                lastLtp = 0.0
                flashToken++
                itemView.background = AppDesign.cardBackground(itemView.context, 10)
                priceView.text = "—"
                priceView.setTextColor(mutedColor)
                changeView.text = "—"
                changeView.setTextColor(mutedColor)
                return
            }

            val previousLtp = lastLtp
            lastLtp = tick.ltp
            if (previousLtp > 0.0 && previousLtp != tick.ltp) {
                flash(
                    if (tick.ltp > previousLtp) upFlashColor else downFlashColor
                )
            }

            priceView.text = String.format(Locale.US, "₹%.2f", tick.ltp)

            val previousClose = tick.previousClose ?: tick.close
            val change = tick.change
                ?: previousClose?.let { tick.ltp - it }

            if (change == null || !change.isFinite()) {
                priceView.setTextColor(titleColor)
                changeView.text = "—"
                changeView.setTextColor(mutedColor)
                return
            }

            val changePercent = tick.changePercent
                ?: previousClose?.takeIf { it > 0.0 }
                    ?.let { change / it * 100.0 }

            val sign = if (change >= 0.0) "+" else "-"
            changeView.text = if (changePercent != null &&
                changePercent.isFinite()
            ) {
                String.format(
                    Locale.US,
                    "%s%.2f (%s%.2f%%)",
                    sign,
                    abs(change),
                    sign,
                    abs(changePercent)
                )
            } else {
                String.format(Locale.US, "%s%.2f", sign, abs(change))
            }

            val color = if (change >= 0.0) upColor else downColor
            priceView.setTextColor(color)
            changeView.setTextColor(color)
        }

        private fun flash(color: Int) {
            val token = ++flashToken
            itemView.background = AppDesign.roundedBackground(
                itemView.context,
                color,
                10
            )
            itemView.postDelayed({
                if (token == flashToken) {
                    itemView.background = AppDesign.cardBackground(
                        itemView.context,
                        10
                    )
                }
            }, 240L)
        }
    }
}
