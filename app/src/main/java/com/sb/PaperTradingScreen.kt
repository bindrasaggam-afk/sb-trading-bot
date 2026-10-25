package com.sb

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

object PaperTradingScreen {

    private const val BG = 0xFF0B1220.toInt()
    private const val PANEL = 0xFF121D2E.toInt()
    private const val PANEL_ALT = 0xFF182538.toInt()
    private const val TEXT = 0xFFF1F5F9.toInt()
    private const val MUTED = 0xFF9AA9BD.toInt()
    private const val BLUE = 0xFF60A5FA.toInt()
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val RED = 0xFFF87171.toInt()
    private const val BORDER = 0xFF2A3A50.toInt()

    fun create(context: Context): View {
        val db = DashboardDbHelper(context)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 18),
                dp(context, 16), dp(context, 28))
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
                textSize = 14f
                setTextColor(TEXT)
                background = rounded(BLUE, 10)
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
                setPadding(dp(context, 14), dp(context, 13),
                    dp(context, 14), dp(context, 13))
                background = rounded(PANEL, 14)
            }

            card.addView(makeText(context, title, 13, MUTED, false))
            card.addView(
                makeText(context, value, 23, TEXT, true).apply {
                    setPadding(0, dp(context, 5), 0, dp(context, 3))
                }
            )
            card.addView(makeText(context, note, 12, MUTED, false))

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

        fun refresh() {
            page.removeAllViews()

            addText("Paper Trading", 26, TEXT, true)
            addText(
                "Local practice mode · No broker connection",
                13, MUTED
            ).setPadding(0, dp(context, 5), 0, dp(context, 12))

            val balance = db.getPaperBalance()
            val orders = db.getPaperOrders()
            val openOrders = orders.filter { it.status == "OPEN" }
            val closedOrders = orders.filter { it.status == "CLOSED" }

            val realizedPnl = closedOrders.sumOfCompat {
                it.realizedPnl ?: 0.0
            }

            addCard(
                "Starting Paper Balance",
                balance?.let { rupees(it) } ?: "Not set",
                "Manually configured amount"
            )

            addCard(
                "Realized Paper P&L",
                rupees(realizedPnl),
                "Calculated only from locally closed orders"
            )

            addCard(
                "Open Positions",
                openOrders.size.toString(),
                "Locally saved paper orders"
            )

            addCard(
                "Trade History",
                closedOrders.size.toString(),
                "Locally closed orders"
            )

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
                        try {
                            db.savePaperBalance(amount)
                            toast(context, "Starting balance saved.")
                            refresh()
                        } catch (e: Exception) {
                            toast(context, "Could not save balance.")
                        }
                    }
                }
            }

            addButton("Create Paper Order") {
                if (balance == null) {
                    toast(context, "First set your starting balance.")
                } else {
                    showCreateOrderDialog(context, db) {
                        refresh()
                    }
                }
            }

            addText("Open Positions", 19, TEXT, true)
                .setPadding(0, dp(context, 22), 0, dp(context, 6))

            if (openOrders.isEmpty()) {
                addCard(
                    "No open paper orders",
                    "—",
                    "Create a paper order to see it here."
                )
            } else {
                openOrders.forEach { order ->
                    val card = orderCard(context, order)

                    val closeButton = Button(context).apply {
                        text = "Close Paper Order"
                        isAllCaps = false
                        setTextColor(TEXT)
                        background = rounded(PANEL_ALT, 9)

                        setOnClickListener {
                            showInputDialog(
                                context,
                                "Close ${order.symbol}",
                                "Exit price",
                                InputType.TYPE_CLASS_NUMBER or
                                    InputType.TYPE_NUMBER_FLAG_DECIMAL
                            ) { input ->
                                val exit = input.toDoubleOrNull()

                                if (exit == null ||
                                    !exit.isFinite() ||
                                    exit <= 0.0
                                ) {
                                    toast(context, "Enter a valid exit price.")
                                } else {
                                    val direction =
                                        if (order.side == "BUY") 1.0 else -1.0

                                    val pnl = (exit - order.entryPrice) *
                                        order.quantity * direction

                                    try {
                                        db.closePaperOrder(
                                            order.id,
                                            exit,
                                            pnl
                                        )
                                        toast(context, "Paper order closed.")
                                        refresh()
                                    } catch (e: Exception) {
                                        toast(context, "Could not close order.")
                                    }
                                }
                            }
                        }
                    }

                    card.addView(
                        closeButton,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(context, 44)
                        ).apply {
                            topMargin = dp(context, 10)
                        }
                    )

                    page.addView(
                        card,
                        matchParams(context).apply {
                            topMargin = dp(context, 8)
                        }
                    )
                }
            }

            addText("Trade History", 19, TEXT, true)
                .setPadding(0, dp(context, 22), 0, dp(context, 6))

            if (closedOrders.isEmpty()) {
                addCard(
                    "No completed paper trades",
                    "—",
                    "Closed orders will appear here."
                )
            } else {
                closedOrders.forEach { order ->
                    page.addView(
                        orderCard(context, order),
                        matchParams(context).apply {
                            topMargin = dp(context, 8)
                        }
                    )
                }
            }

            addText(
                "Offline practice only. Starting balance is a configured value, " +
                    "not a live account balance. No broker orders are sent.",
                12,
                MUTED
            ).setPadding(0, dp(context, 20), 0, 0)
        }

        refresh()
        return scroll
    }

    private fun showCreateOrderDialog(
        context: Context,
        db: DashboardDbHelper,
        onSaved: () -> Unit
    ) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8),
                dp(context, 20), dp(context, 4))
        }

        fun field(
            hintText: String,
            type: Int
        ): EditText {
            val edit = EditText(context).apply {
                hint = hintText
                inputType = type
                setSingleLine(true)
                setTextColor(TEXT)
                setHintTextColor(MUTED)
            }
            layout.addView(edit)
            return edit
        }

        val symbol = field(
            "Symbol (e.g. RELIANCE)",
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )

        val quantity = field(
            "Quantity",
            InputType.TYPE_CLASS_NUMBER
        )

        val price = field(
            "Entry price",
            InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL
        )

        var side = "BUY"

        val sideButton = Button(context).apply {
            text = "Side: BUY (tap to change)"
            isAllCaps = false
            setOnClickListener {
                side = if (side == "BUY") "SELL" else "BUY"
                text = "Side: $side (tap to change)"
            }
        }

        layout.addView(sideButton)

        AlertDialog.Builder(context)
            .setTitle("Create Local Paper Order")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save Order", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            val symbolValue = symbol.text.toString().trim()
                            val quantityValue = quantity.text.toString()
                                .trim().toIntOrNull()
                            val priceValue = price.text.toString()
                                .trim().toDoubleOrNull()

                            if (symbolValue.isBlank()) {
                                toast(context, "Enter a symbol.")
                                return@setOnClickListener
                            }

                            if (quantityValue == null ||
                                quantityValue <= 0
                            ) {
                                toast(context, "Enter a valid quantity.")
                                return@setOnClickListener
                            }

                            if (priceValue == null ||
                                !priceValue.isFinite() ||
                                priceValue <= 0.0
                            ) {
                                toast(context, "Enter a valid entry price.")
                                return@setOnClickListener
                            }

                            try {
                                db.savePaperOrder(
                                    symbolValue,
                                    side,
                                    quantityValue,
                                    priceValue
                                )

                                dialog.dismiss()
                                toast(context, "Paper order saved locally.")
                                onSaved()
                            } catch (e: Exception) {
                                toast(context, "Could not save paper order.")
                            }
                        }
                }

                dialog.show()
            }
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

        dialog.show()
    }

    private fun orderCard(
        context: Context,
        order: PaperOrder
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 13),
                dp(context, 14), dp(context, 13))
            background = rounded(PANEL, 13)
        }

        card.addView(
            makeText(
                context,
                "${order.symbol} · ${order.side}",
                17,
                if (order.side == "BUY") GREEN else RED,
                true
            )
        )

        card.addView(
            makeText(
                context,
                "Quantity: ${order.quantity}  |  Entry: ${rupees(order.entryPrice)}",
                13, TEXT, false
            ).apply {
                setPadding(0, dp(context, 7), 0, 0)
            }
        )

        card.addView(
            makeText(
                context,
                "Status: ${order.status}",
                12, MUTED, false
            ).apply {
                setPadding(0, dp(context, 5), 0, 0)
            }
        )

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
        color: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpFloat(radiusDp)
            setStroke(dpStatic(1), BORDER)
        }
    }

    private fun matchParams(
        context: Context
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun rupees(value: Double): String {
        return "₹" + String.format(Locale.US, "%.2f", value)
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f)
            .toInt()
    }

    private fun dpFloat(value: Int): Float = value.toFloat()

    private fun dpStatic(value: Int): Int = value

    private inline fun <T> Iterable<T>.sumOfCompat(
        selector: (T) -> Double
    ): Double {
        var total = 0.0
        for (item in this) total += selector(item)
        return total
    }
}
