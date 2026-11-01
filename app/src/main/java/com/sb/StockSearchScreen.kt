
package com.sb

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

private data class UniversalSearchItem(
    val title: String,
    val subtitle: String,
    val screen: String? = null,
    val stock: Stock? = null
)

class StockSearchScreen(
    private val context: Context,
    private val repository: StockRepository = StockRepository(context),
    private val onOpenScreen: ((String) -> Unit)? = null,
    private val onStockSelected: ((Stock) -> Unit)? = null
) {
    private val appScreens = listOf(
        UniversalSearchItem(
            "Dashboard",
            "Home • Account overview • Quick access",
            screen = "Home"
        ),
        UniversalSearchItem(
            "Live Signals",
            "Signals • Trading signal screen",
            screen = "Signals"
        ),
        UniversalSearchItem(
            "Paper Trading",
            "Paper • Practice orders and trading",
            screen = "Paper"
        ),
        UniversalSearchItem(
            "Open Positions",
            "Positions • View current positions",
            screen = "Positions"
        ),
        UniversalSearchItem(
            "Market Watch",
            "Market • Equity, Index, MCX and ETFs",
            screen = "Market"
        ),
        UniversalSearchItem(
            "Chart",
            "Chart • Heikin Ashi and candle view",
            screen = "Chart"
        ),
        UniversalSearchItem(
            "Settings",
            "Settings • App configuration",
            screen = "Settings"
        )
    )

    fun createView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val title = TextView(context).apply {
            text = "Universal Search"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }

        val count = TextView(context).apply {
            text = "Loaded instruments: ${repository.getStockCount()}"
            textSize = 14f
            setPadding(0, 8, 0, 12)
        }

        val searchBox = EditText(context).apply {
            hint = "Search stocks, symbols, ISIN or app screens"
            setSingleLine(true)
            textSize = 15f
            setPadding(12, 12, 12, 12)
        }

        val results = ListView(context)

        val adapter = object : ArrayAdapter<UniversalSearchItem>(
            context,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            mutableListOf<UniversalSearchItem>()
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val view = super.getView(position, convertView, parent)
                val item = getItem(position) ?: return view

                view.findViewById<TextView>(
                    android.R.id.text1
                ).text = item.title

                view.findViewById<TextView>(
                    android.R.id.text2
                ).text = item.subtitle

                return view
            }
        }

        results.adapter = adapter

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                adapter.clear()

                val query = s?.toString()?.trim().orEmpty()
                if (query.isBlank()) return

                try {
                    val screenResults = appScreens.filter { item ->
                        item.title.contains(query, ignoreCase = true) ||
                        item.subtitle.contains(query, ignoreCase = true)
                    }

                    val stockResults = repository.searchStocks(
                        query,
                        100
                    ).map { stock ->
                        UniversalSearchItem(
                            title = "${stock.symbol} • ${stock.exchange}",
                            subtitle =
                                "${stock.name} | ${stock.instrumentType}" +
                                (stock.isin?.let { " | $it" } ?: ""),
                            stock = stock
                        )
                    }

                    adapter.addAll(screenResults + stockResults)
                    adapter.notifyDataSetChanged()

                    if (adapter.isEmpty()) {
                        Toast.makeText(
                            context,
                            "No matching screens or stocks found",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Search failed: ${e.message ?: "Unknown error"}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        results.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
                ?: return@setOnItemClickListener

            when {
                item.screen != null -> {
                    val openScreen = onOpenScreen

                    if (openScreen != null) {
                        openScreen(item.screen)
                    } else {
                        Toast.makeText(
                            context,
                            "Screen navigation is not connected",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                item.stock != null -> {
                    val stock = item.stock
                    val selectStock = onStockSelected

                    if (selectStock != null) {
                        selectStock(stock)
                    } else {
                        Toast.makeText(
                            context,
                            "${stock.name} (${stock.exchange}:${stock.symbol})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        root.addView(title)
        root.addView(count)
        root.addView(searchBox)

        root.addView(
            results,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        return root
    }
}