package com.sb

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sb.backend.core.BackendManager
import com.sb.backend.models.OrderSide
import com.sb.backend.models.PaperExecution
import com.sb.backend.models.PaperOrder
import com.sb.backend.models.Position
import com.sb.backend.services.PaperSnapshot
import com.sb.backend.services.PaperTradingEngine
import com.sb.backend.services.TradeResult
import com.sb.ui.theme.AppDesign
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object PaperTradingScreen {

    private val BG = AppDesign.background
    private val PANEL = AppDesign.surfaceSolid
    private val PANEL_ALT = AppDesign.surface
    private val TEXT = AppDesign.textPrimary
    private val MUTED = AppDesign.textMuted
    private val BLUE = AppDesign.accent
    private val GREEN = AppDesign.positive
    private val RED = AppDesign.negative

    private data class ScreenData(
        val snapshot: PaperSnapshot?,
        val orders: List<PaperOrder>,
        val executions: List<PaperExecution>
    )

    fun create(
        context: Context,
        onOpenChart: ((String, String) -> Unit)? = null
    ): View {
        val handler = Handler(Looper.getMainLooper())
        val executor = Executors.newSingleThreadExecutor()

        val scroll = ScrollView(context).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 12),
                dp(context, 14), dp(context, 20))
        }

        scroll.addView(page)

        fun addText(
            value: String,
            size: Int,
            color: Int,
            bold: Boolean = false
        ): TextView {
            val view = TextView(context).apply {
                text = value
                textSize = size.toFloat()
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            page.addView(view)
            return view
        }

        fun addButton(
            title: String,
            action: () -> Unit
        ) {
            val button = Button(context).apply {
                text = title
                isAllCaps = false
                textSize = 13f
                minHeight = 0
                minimumHeight = 0
                setTextColor(TEXT)
                background = rounded(context, BLUE, 9)
                setOnClickListener { action() }
            }

            page.addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(context, 48)
                ).apply {
                    topMargin = dp(context, 10)
                }
            )
        }

        fun addCard(title: String, value: String, note: String) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 13), dp(context, 10),
                    dp(context, 13), dp(context, 10))
                background = rounded(context, PANEL, 10)
            }

            card.addView(makeText(context, title, 11, MUTED, false))
            card.addView(
                makeText(context, value, 19, TEXT, true).apply {
                    setPadding(0, dp(context, 3), 0, dp(context, 2))
                }
            )
            card.addView(makeText(context, note, 10, MUTED, false))

            page.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(context, 8)
                }
            )
        }

        var detached = false
        var loading = false
        var refreshQueued = false
        var retryScheduled = false

        lateinit var requestRefresh: () -> Unit
        lateinit var runOperation: ((PaperTradingEngine) -> TradeResult) -> Unit

        fun scheduleRetry() {
            if (detached || retryScheduled) return
            retryScheduled = true
            handler.postDelayed({
                retryScheduled = false
                requestRefresh()
            }, 700L)
        }

        fun renderLoading(message: String) {
            page.removeAllViews()
            addText("Paper Trading", 20, TEXT, true)
            addText(
                "Unified paper engine · sb_backend.db",
                11, MUTED
            ).setPadding(0, dp(context, 3), 0, dp(context, 8))
            addCard("Paper Trading", "Preparing…", message)
        }

        fun renderError(message: String) {
            page.removeAllViews()
            addText("Paper Trading", 20, TEXT, true)
            addText(
                "Unified paper engine · sb_backend.db",
                11, MUTED
            ).setPadding(0, dp(context, 3), 0, dp(context, 8))
            addCard("Paper Trading unavailable", "—", message)
            addButton("Retry") {
                requestRefresh()
            }
        }

        fun render(data: ScreenData) {
            page.removeAllViews()

            addText("Paper Trading", 20, TEXT, true)
            addText(
                "Automated from genuine Research Signals · sb_backend.db",
                11, MUTED
            ).setPadding(0, dp(context, 3), 0, dp(context, 8))

            val snapshot = data.snapshot
            val orders = data.orders
            val ordersById = orders.associateBy { it.id }

            if (snapshot == null) {
                addCard(
                    "Paper Account",
                    "Not configured",
                    "Set a starting balance to begin paper trading."
                )
            } else {
                val account = snapshot.account
                addCard(
                    "Available Balance",
                    rupees(account.availableBalance),
                    "Cash minus reserved short margin"
                )
                addCard(
                    "Realized P&L",
                    rupees(snapshot.realizedPnl),
                    "Closed paper trades"
                )
                addCard(
                    "Unrealized P&L",
                    rupees(snapshot.unrealizedPnl),
                    "Open positions marked to market"
                )
                addCard(
                    "Total P&L",
                    rupees(snapshot.realizedPnl + snapshot.unrealizedPnl),
                    "Realized plus unrealized"
                )
                addCard(
                    "Total Equity",
                    rupees(snapshot.totalEquity),
                    "Starting balance plus realized and unrealized P&L"
                )
                addCard(
                    "Paper Cash Balance",
                    rupees(account.balance),
                    "BUY cost deducted; short proceeds excluded"
                )
                addCard(
                    "Reserved Margin",
                    rupees(account.reservedMargin),
                    "Short-sale margin held by the engine"
                )
            }

            if (snapshot == null || orders.isEmpty()) {
                addButton("Set Starting Balance") {
                    showInputDialog(
                        context,
                        "Set Starting Paper Balance",
                        "Amount in ₹",
                        InputType.TYPE_CLASS_NUMBER or
                            InputType.TYPE_NUMBER_FLAG_DECIMAL
                    ) { input ->
                        val amount = input.toDoubleOrNull()

                        if (amount == null ||
                            !amount.isFinite() ||
                            amount <= 0.0
                        ) {
                            toast(context, "Enter a valid positive amount.")
                        } else {
                            runOperation { engine ->
                                engine.setStartingBalance(amount)
                            }
                        }
                    }
                }
            }

            addText("Pending Signal Entries", 17, TEXT, true)
                .setPadding(0, dp(context, 16), 0, dp(context, 4))

            val pendingOrders = orders.filter { it.isPending }
            if (pendingOrders.isEmpty()) {
                addCard(
                    "No pending entries",
                    "—",
                    "New genuine signals will wait here for entry."
                )
            } else {
                pendingOrders.forEach { order ->
                    page.addView(
                        orderCard(context, order, onOpenChart),
                        matchParams().apply {
                            topMargin = dp(context, 8)
                        }
                    )
                }
            }

            addText("Open Positions", 17, TEXT, true)
                .setPadding(0, dp(context, 16), 0, dp(context, 4))

            val openPositions = snapshot?.openPositions.orEmpty()
            if (openPositions.isEmpty()) {
                addCard(
                    "No open positions",
                    "—",
                    "Entries open automatically when price reaches a signal range."
                )
            } else {
                openPositions.forEach { position ->
                    val order = ordersById[position.orderId]
                    val card = positionCard(
                        context,
                        position,
                        order,
                        onOpenChart
                    )

                    page.addView(
                        card,
                        matchParams().apply {
                            topMargin = dp(context, 8)
                        }
                    )
                }
            }

            addText("Order History", 17, TEXT, true)
                .setPadding(0, dp(context, 16), 0, dp(context, 4))

            if (orders.isEmpty()) {
                addCard(
                    "No paper orders",
                    "—",
                    "Forwarded genuine signals will appear here."
                )
            } else {
                orders.forEach { order ->
                    page.addView(
                        orderCard(context, order, onOpenChart),
                        matchParams().apply {
                            topMargin = dp(context, 8)
                        }
                    )
                }
            }

            addText("Recent Executions", 17, TEXT, true)
                .setPadding(0, dp(context, 16), 0, dp(context, 4))

            if (data.executions.isEmpty()) {
                addCard(
                    "No executions",
                    "—",
                    "Entries, partial targets and exits will appear here."
                )
            } else {
                data.executions.take(30).forEach { execution ->
                    page.addView(
                        executionCard(context, execution),
                        matchParams().apply {
                            topMargin = dp(context, 8)
                        }
                    )
                }
            }

            addText(
                "Practice only. Trades are opened, scaled out and closed " +
                    "automatically from genuine signals. No broker orders " +
                    "are sent and the original signal formulas are unchanged.",
                12,
                MUTED
            ).setPadding(0, dp(context, 20), 0, 0)
        }

        runOperation = { action ->
            try {
                executor.execute {
                    val result = try {
                        action(BackendManager.paperEngine())
                    } catch (error: Exception) {
                        TradeResult(
                            success = false,
                            message = error.message ?: "Paper operation failed."
                        )
                    }

                    handler.post {
                        if (detached) return@post
                        toast(context, operationMessage(result))
                        requestRefresh()
                    }
                }
            } catch (_: RejectedExecutionException) {
                if (!detached) {
                    toast(context, "Paper trading is stopping.")
                }
            }
        }

        requestRefresh = {
            if (detached) {
                Unit
            } else if (!BackendManager.isInitialized()) {
                renderLoading("Backend is not initialized yet.")
                scheduleRetry()
            } else if (!BackendManager.isReady()) {
                renderLoading("Backend is starting. This may take a moment.")
                scheduleRetry()
            } else if (loading) {
                refreshQueued = true
            } else {
                loading = true
                try {
                    executor.execute {
                        val result = runCatching { loadData() }

                        handler.post {
                            if (detached) return@post
                            loading = false
                            result.onSuccess { render(it) }
                            result.onFailure { error ->
                                renderError(
                                    error.message ?: "Could not load paper trading."
                                )
                            }

                            if (refreshQueued) {
                                refreshQueued = false
                                requestRefresh()
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    loading = false
                    renderError("Paper trading is stopping.")
                }
            }
        }

        scroll.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    detached = true
                    handler.removeCallbacksAndMessages(null)
                    executor.shutdownNow()
                }
            }
        )

        val periodicRefresh = object : Runnable {
            override fun run() {
                if (detached) return
                requestRefresh()
                handler.postDelayed(this, 5_000L)
            }
        }

        renderLoading("Connecting to the paper trading engine…")
        requestRefresh()
        handler.postDelayed(periodicRefresh, 5_000L)
        return scroll
    }

    private fun loadData(): ScreenData {
        val engine = BackendManager.paperEngine()
        try {
            engine.markToMarket()
        } catch (_: Exception) {
            // Keep the last known marks when live quotes are unavailable.
        }
        return ScreenData(
            snapshot = engine.snapshot(),
            orders = engine.getOrders(),
            executions = engine.getExecutions(limit = 100)
        )
    }

    private fun showInputDialog(
        context: Context,
        title: String,
        hint: String,
        inputType: Int,
        onSubmit: (String) -> Unit
    ) {
        val input = EditText(context).apply {
            this.hint = hint
            this.inputType = inputType
            setSingleLine(true)
            setPadding(dp(context, 18), dp(context, 12),
                dp(context, 18), dp(context, 12))
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val value = input.text.toString().trim()

                    if (value.isBlank()) {
                        toast(context, "Enter a value.")
                    } else {
                        onSubmit(value)
                        dialog.dismiss()
                    }
                }
        }

        AppDesign.styleDialog(dialog)
        dialog.show()
    }

    private fun positionCard(
        context: Context,
        position: Position,
        order: PaperOrder?,
        onOpenChart: ((String, String) -> Unit)? = null
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 13), dp(context, 10),
                dp(context, 13), dp(context, 10))
            background = rounded(context, PANEL, 10)
            isClickable = true
            setOnClickListener {
                onOpenChart?.invoke(position.symbol, position.exchange)
            }
        }

        card.addView(
            makeText(
                context,
                "${position.symbol} · ${position.side}",
                17,
                if (position.side == OrderSide.BUY.name) GREEN else RED,
                true
            )
        )

        card.addView(
            makeText(
                context,
                "Quantity: ${position.quantity}  |  " +
                    "Entry: ${rupees(position.entryPrice)}  |  " +
                    "LTP: ${rupees(position.lastPrice)}",
                13, TEXT, false
            ).apply {
                setPadding(0, dp(context, 7), 0, 0)
            }
        )

        card.addView(
            makeText(
                context,
                "Unrealized P&L: ${rupees(position.unrealizedPnl)}",
                14,
                if (position.unrealizedPnl >= 0) GREEN else RED,
                true
            ).apply {
                setPadding(0, dp(context, 5), 0, 0)
            }
        )

        if (order != null) {
            card.addView(
                makeText(
                    context,
                    "SL: ${order.stopLoss?.let { rupees(it) } ?: "—"}  |  " +
                        "T1: ${order.target1?.let { rupees(it) } ?: "—"}  |  " +
                        "T2: ${order.target2?.let { rupees(it) } ?: "—"}  |  " +
                        "T3: ${order.target3?.let { rupees(it) } ?: "—"}",
                    12, MUTED, false
                ).apply {
                    setPadding(0, dp(context, 5), 0, 0)
                }
            )

            card.addView(
                makeText(
                    context,
                    "Remaining: ${order.remainingQuantity}  |  " +
                        "Signal: ${order.signalCategory.ifBlank { "—" }} · " +
                        "${order.strategyId.ifBlank { "—" }}",
                    12, MUTED, false
                ).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                }
            )
        }

        return card
    }

    private fun orderCard(
        context: Context,
        order: PaperOrder,
        onOpenChart: ((String, String) -> Unit)? = null
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 13), dp(context, 10),
                dp(context, 13), dp(context, 10))
            background = rounded(context, PANEL, 10)
            isClickable = true
            setOnClickListener {
                onOpenChart?.invoke(order.symbol, order.exchange)
            }
        }

        card.addView(
            makeText(
                context,
                "${order.symbol} · ${order.side} · ${order.exchange}",
                17,
                if (order.side == OrderSide.BUY.name) GREEN else RED,
                true
            )
        )

        card.addView(
            makeText(
                context,
                "Quantity: ${order.quantity}  |  " +
                    "Remaining: ${order.remainingQuantity}  |  " +
                    "Entry: ${rupees(order.entryPrice)}",
                13, TEXT, false
            ).apply {
                setPadding(0, dp(context, 7), 0, 0)
            }
        )

        card.addView(
            makeText(
                context,
                "Status: ${order.status}  |  " +
                    "Signal: ${order.signalCategory.ifBlank { "—" }} · " +
                    "${order.strategyId.ifBlank { "—" }} " +
                    "v${order.strategyVersion}",
                12, MUTED, false
            ).apply {
                setPadding(0, dp(context, 5), 0, 0)
            }
        )

        if (order.entryLow != null || order.entryHigh != null) {
            card.addView(
                makeText(
                    context,
                    "Entry range: ${order.entryLow?.let { rupees(it) } ?: "—"}" +
                        " – ${order.entryHigh?.let { rupees(it) } ?: "—"}",
                    12, MUTED, false
                ).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                }
            )
        }

        if (order.exitPrice != null) {
            card.addView(
                makeText(
                    context,
                    "Exit: ${rupees(order.exitPrice)}",
                    13, TEXT, false
                ).apply {
                    setPadding(0, dp(context, 5), 0, 0)
                }
            )
        }

        if (order.realizedPnl != null) {
            card.addView(
                makeText(
                    context,
                    "Realized P&L: ${rupees(order.realizedPnl)}",
                    14,
                    if (order.realizedPnl >= 0) GREEN else RED,
                    true
                ).apply {
                    setPadding(0, dp(context, 5), 0, 0)
                }
            )
        }

        card.addView(
            makeText(
                context,
                "SL: ${order.stopLoss?.let { rupees(it) } ?: "—"}  |  " +
                    "T1: ${order.target1?.let { rupees(it) } ?: "—"}  |  " +
                    "T2: ${order.target2?.let { rupees(it) } ?: "—"}  |  " +
                    "T3: ${order.target3?.let { rupees(it) } ?: "—"}",
                12, MUTED, false
            ).apply {
                setPadding(0, dp(context, 5), 0, 0)
            }
        )

        if (order.exitReason.isNotBlank()) {
            card.addView(
                makeText(
                    context,
                    "Exit reason: ${order.exitReason}",
                    12, MUTED, false
                ).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                }
            )
        }

        return card
    }

    private fun executionCard(
        context: Context,
        execution: PaperExecution
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 13), dp(context, 10),
                dp(context, 13), dp(context, 10))
            background = rounded(context, PANEL, 10)
        }

        card.addView(
            makeText(
                context,
                "#${execution.orderId} · ${execution.kind}",
                14, TEXT, true
            )
        )

        card.addView(
            makeText(
                context,
                "Qty: ${execution.quantity}  |  " +
                    "Price: ${rupees(execution.price)}  |  " +
                    "Net P&L: ${rupees(execution.netPnl)}",
                13, TEXT, false
            ).apply {
                setPadding(0, dp(context, 5), 0, 0)
            }
        )

        if (execution.note.isNotBlank()) {
            card.addView(
                makeText(
                    context,
                    execution.note,
                    12, MUTED, false
                ).apply {
                    setPadding(0, dp(context, 4), 0, 0)
                }
            )
        }

        return card
    }

    private fun makeText(
        context: Context,
        value: String,
        size: Int,
        color: Int,
        bold: Boolean
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun rounded(
        context: Context,
        color: Int,
        radiusDp: Int
    ): GradientDrawable {
        return AppDesign.roundedBackground(context, color, radiusDp)
    }

    private fun matchParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rupees(value: Double): String {
        return "₹" + String.format(Locale.US, "%.2f", value)
    }

    private fun operationMessage(result: TradeResult): String {
        val pnl = result.pnl
        return if (result.success && pnl != null) {
            "${result.message} P&L: ${rupees(pnl)}"
        } else {
            result.message
        }
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f)
            .toInt()
    }
}
