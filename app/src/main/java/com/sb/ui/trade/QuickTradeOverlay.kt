package com.sb.ui.trade

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sb.backend.market.model.MarketTick
import com.sb.ui.theme.AppDesign
import java.util.Locale
import kotlin.math.max

data class QuickTradeRequest(
    val symbol: String,
    val exchange: String,
    val side: String,
    val quantity: Int,
    val entryPrice: Double,
    val stopLoss: Double,
    val target: Double
)

data class QuickTradeTracking(
    val orderId: Long,
    val symbol: String,
    val exchange: String,
    val side: String,
    val quantity: Int,
    val entryPrice: Double,
    val stopLoss: Double,
    val target: Double
)

class QuickTradeOverlay(
    private val context: Context,
    private val onOrderRequest: (QuickTradeRequest) -> Unit,
    private val onExitRequest: (QuickTradeTracking, Double, String) -> Unit
) {

    private val accentColor = AppDesign.accent
    private val textColor = AppDesign.textPrimary
    private val mutedColor = AppDesign.textMuted
    private val greenColor = AppDesign.positive
    private val redColor = AppDesign.negative

    private var symbol = ""
    private var exchange = ""
    private var lastTick: MarketTick? = null
    private var lastLtp = 0.0
    private var activeTrade: QuickTradeTracking? = null

    private lateinit var bodyContainer: LinearLayout
    private lateinit var collapseButton: TextView
    private lateinit var symbolText: TextView
    private lateinit var ltpText: TextView
    private lateinit var qtyInput: EditText
    private lateinit var stopLossInput: EditText
    private lateinit var targetInput: EditText
    private lateinit var statusText: TextView

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f

    val view: View = buildPanel()

    fun setInstrument(newSymbol: String, newExchange: String) {
        if (symbol.equals(newSymbol, ignoreCase = true) &&
            exchange.equals(newExchange, ignoreCase = true)
        ) {
            return
        }
        symbol = newSymbol
        exchange = newExchange
        activeTrade = null
        lastTick = null
        lastLtp = 0.0
        symbolText.text = newSymbol.ifBlank { "—" }
        ltpText.text = "—"
        ltpText.setTextColor(mutedColor)
        stopLossInput.setText("")
        targetInput.setText("")
        statusText.text = "Live price ka wait... "
        statusText.setTextColor(mutedColor)
    }

    fun onTick(tick: MarketTick) {
        if (symbol.isNotBlank() &&
            !symbol.equals(tick.key.symbol, ignoreCase = true)
        ) {
            return
        }

        if (tick.ltp <= 0.0 || !tick.ltp.isFinite()) return

        lastTick = tick
        ltpText.text = String.format(Locale.US, "₹%.2f", tick.ltp)
        ltpText.setTextColor(
            when {
                lastLtp <= 0.0 -> textColor
                tick.ltp > lastLtp -> greenColor
                tick.ltp < lastLtp -> redColor
                else -> textColor
            }
        )
        lastLtp = tick.ltp

        val trade = activeTrade ?: return
        val direction = if (trade.side == "BUY") 1.0 else -1.0
        val pnl = (tick.ltp - trade.entryPrice) * trade.quantity * direction
        statusText.setTextColor(if (pnl >= 0.0) greenColor else redColor)
        statusText.text = buildString {
            append(trade.side).append(' ').append(trade.quantity)
            append(" @ ").append(formatPrice(trade.entryPrice))
            append("  •  LTP ").append(formatPrice(tick.ltp))
            append('\n')
            append("P&L ₹").append(formatPrice(pnl))
            append("  •  SL ").append(formatPrice(trade.stopLoss))
            append("  •  TGT ").append(formatPrice(trade.target))
        }

        val stopLossHit = if (direction > 0.0) {
            tick.ltp <= trade.stopLoss
        } else {
            tick.ltp >= trade.stopLoss
        }
        val targetHit = if (direction > 0.0) {
            tick.ltp >= trade.target
        } else {
            tick.ltp <= trade.target
        }

        if (!stopLossHit && !targetHit) return

        val reason = if (stopLossHit) "STOP LOSS HIT" else "TARGET HIT"
        activeTrade = null
        statusText.setTextColor(if (pnl >= 0.0) greenColor else redColor)
        statusText.text = buildString {
            append(reason)
            append("  •  ").append(trade.side).append(' ').append(trade.quantity)
            append(" @ ").append(formatPrice(tick.ltp))
            append('\n')
            append("P&L ₹").append(formatPrice(pnl))
        }
        onExitRequest(trade, tick.ltp, reason)
    }

    fun onOrderPlaced(trade: QuickTradeTracking) {
        activeTrade = trade
        statusText.setTextColor(accentColor)
        statusText.text = buildString {
            append("OPEN ").append(trade.side).append(' ').append(trade.quantity)
            append(" @ ").append(formatPrice(trade.entryPrice))
            append("  •  SL ").append(formatPrice(trade.stopLoss))
            append("  •  TGT ").append(formatPrice(trade.target))
        }
        lastTick?.let { onTick(it) }
    }

    fun onOrderRejected(message: String) {
        activeTrade = null
        statusText.setTextColor(redColor)
        statusText.text = message
    }

    fun onTradeClosed(message: String, pnl: Double?) {
        statusText.setTextColor(
            if (pnl != null && pnl >= 0.0) greenColor else redColor
        )
        statusText.text = if (pnl != null) {
            String.format(Locale.US, "%s  •  P&L ₹%.2f", message, pnl)
        } else {
            message
        }
    }

    fun showMessage(message: String) {
        statusText.setTextColor(mutedColor)
        statusText.text = message
    }

    fun submit(side: String) {
        placeOrder(side)
    }

    private fun buildPanel(): View {
        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = AppDesign.overlayBackground(context, 12)
        panel.setPadding(dp(8), dp(3), dp(8), dp(6))
        panel.elevation = dp(6).toFloat()

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(0, dp(1), 0, dp(3))

        val dragHandle = TextView(context).apply {
            text = "≡"
            textSize = 16f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(6), 0)
        }
        header.addView(dragHandle)

        val title = TextView(context).apply {
            text = "QUICK TRADE"
            textSize = 10.5f
            setTextColor(accentColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        collapseButton = TextView(context).apply {
            text = "▸"
            textSize = 14f
            setTextColor(mutedColor)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(2), 0)
            setOnClickListener {
                val collapsing = bodyContainer.visibility == View.VISIBLE
                bodyContainer.visibility = if (collapsing) View.GONE else View.VISIBLE
                text = if (collapsing) "▸" else "▾"
            }
        }
        header.addView(collapseButton)

        panel.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panel.addView(
            bodyContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val priceRow = LinearLayout(context)
        priceRow.orientation = LinearLayout.HORIZONTAL
        priceRow.gravity = Gravity.CENTER_VERTICAL

        symbolText = TextView(context).apply {
            text = "—"
            textSize = 12f
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        priceRow.addView(
            symbolText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        ltpText = TextView(context).apply {
            text = "—"
            textSize = 13f
            setTextColor(mutedColor)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }
        priceRow.addView(ltpText)

        bodyContainer.addView(
            priceRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val qtyRow = LinearLayout(context)
        qtyRow.orientation = LinearLayout.HORIZONTAL
        qtyRow.gravity = Gravity.CENTER_VERTICAL
        qtyRow.setPadding(0, dp(4), 0, dp(4))

        val qtyLabel = TextView(context).apply {
            text = "QTY"
            textSize = 10f
            setTextColor(mutedColor)
        }
        qtyRow.addView(qtyLabel)

        val minusButton = makeTinyButton("−")
        minusButton.setOnClickListener {
            val current = qtyInput.text.toString().toIntOrNull() ?: 1
            qtyInput.setText(max(1, current - 1).toString())
            qtyInput.setSelection(qtyInput.text.length)
        }
        qtyRow.addView(minusButton)

        qtyInput = makeNumberField("1", decimal = false)
        qtyInput.setText("1")
        qtyRow.addView(
            qtyInput,
            LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        )

        val plusButton = makeTinyButton("+")
        plusButton.setOnClickListener {
            val current = qtyInput.text.toString().toIntOrNull() ?: 1
            qtyInput.setText((current + 1).toString())
            qtyInput.setSelection(qtyInput.text.length)
        }
        qtyRow.addView(plusButton)

        bodyContainer.addView(qtyRow)

        val levelRow = LinearLayout(context)
        levelRow.orientation = LinearLayout.HORIZONTAL
        levelRow.setPadding(0, 0, 0, dp(4))

        stopLossInput = makeNumberField("SL", decimal = true)
        levelRow.addView(
            stopLossInput,
            LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        targetInput = makeNumberField("Target", decimal = true)
        levelRow.addView(
            targetInput,
            LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        )

        bodyContainer.addView(levelRow)

        val actionRow = LinearLayout(context)
        actionRow.orientation = LinearLayout.HORIZONTAL

        val buyButton = Button(context).apply {
            text = "BUY"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = AppDesign.roundedBackground(context, greenColor, 9)
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { placeOrder("BUY") }
        }
        actionRow.addView(
            buyButton,
            LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        val sellButton = Button(context).apply {
            text = "SELL"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = AppDesign.roundedBackground(context, redColor, 9)
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { placeOrder("SELL") }
        }
        actionRow.addView(
            sellButton,
            LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            }
        )

        bodyContainer.addView(actionRow)

        statusText = TextView(context).apply {
            text = "Live price ka wait..."
            textSize = 9.5f
            setTextColor(mutedColor)
            typeface = Typeface.MONOSPACE
            maxLines = 3
            setPadding(0, dp(4), 0, 0)
        }
        bodyContainer.addView(statusText)

        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = view.x
                    dragStartY = view.y
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val parent = view.parent as? ViewGroup
                    if (parent == null || parent.width <= 0 || parent.height <= 0) {
                        return@setOnTouchListener true
                    }
                    val maxX = max(0f, parent.width - view.width.toFloat())
                    val maxY = max(0f, parent.height - view.height.toFloat())
                    view.x = (dragStartX + event.rawX - dragStartRawX)
                        .coerceIn(0f, maxX)
                    view.y = (dragStartY + event.rawY - dragStartRawY)
                        .coerceIn(0f, maxY)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        return panel
    }

    private fun placeOrder(side: String) {
        if (activeTrade != null) {
            showMessage("Ek trade pehle se open hai. Exit ka wait karo.")
            return
        }

        val tick = lastTick
        if (tick == null || tick.ltp <= 0.0 || !tick.ltp.isFinite()) {
            showMessage("Live price available nahi hai. Feed ka wait karo.")
            return
        }

        val quantity = qtyInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1
        qtyInput.setText(quantity.toString())

        val enteredStopLoss = stopLossInput.text.toString().toDoubleOrNull()
        val enteredTarget = targetInput.text.toString().toDoubleOrNull()

        val stopLoss = enteredStopLoss?.takeIf { it > 0.0 && it.isFinite() }
            ?: defaultStopLoss(side, tick.ltp)
        val target = enteredTarget?.takeIf { it > 0.0 && it.isFinite() }
            ?: defaultTarget(side, tick.ltp)

        val validLevels = if (side == "BUY") {
            stopLoss < tick.ltp && target > tick.ltp
        } else {
            stopLoss > tick.ltp && target < tick.ltp
        }

        if (!validLevels) {
            showMessage(
                if (side == "BUY") {
                    "BUY: SL LTP se neeche, Target LTP se upar hona chahiye."
                } else {
                    "SELL: SL LTP se upar, Target LTP se neeche hona chahiye."
                }
            )
            return
        }

        stopLossInput.setText(String.format(Locale.US, "%.2f", stopLoss))
        targetInput.setText(String.format(Locale.US, "%.2f", target))

        statusText.setTextColor(accentColor)
        statusText.text = "Sending $side $quantity @ ${formatPrice(tick.ltp)}..."
        onOrderRequest(
            QuickTradeRequest(
                symbol = symbol.ifBlank { tick.key.symbol },
                exchange = exchange.ifBlank { tick.key.exchange },
                side = side,
                quantity = quantity,
                entryPrice = tick.ltp,
                stopLoss = stopLoss,
                target = target
            )
        )
    }

    private fun defaultStopLoss(side: String, ltp: Double): Double {
        return if (side == "BUY") ltp * 0.995 else ltp * 1.005
    }

    private fun defaultTarget(side: String, ltp: Double): Double {
        return if (side == "BUY") ltp * 1.01 else ltp * 0.99
    }

    private fun makeTinyButton(label: String): Button {
        return Button(context).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            setTextColor(textColor)
            background = AppDesign.fieldBackground(context, 8)
            minWidth = dp(32)
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
        }
    }

    private fun makeNumberField(hintText: String, decimal: Boolean): EditText {
        return EditText(context).apply {
            hint = hintText
            textSize = 11f
            setTextColor(textColor)
            setHintTextColor(mutedColor)
            background = AppDesign.fieldBackground(context, 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            maxLines = 1
            inputType = if (decimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
    }

    private fun formatPrice(value: Double): String {
        if (!value.isFinite()) return "—"
        return String.format(Locale.US, "%.2f", value)
    }

    private fun dp(value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density + 0.5f).toInt()
    }
}
