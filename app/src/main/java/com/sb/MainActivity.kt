
package com.sb

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sb.backend.core.BackendManager
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.model.InstrumentKey
import com.sb.backend.market.repo.MarketFeedRepository
import com.sb.backend.models.Instrument
import com.sb.backend.repositories.InstrumentRepository
import com.sb.ui.market.InstrumentLiveViewModel
import com.sb.ui.market.MarketListAdapter
import com.sb.ui.market.MarketListItem
import com.sb.ui.theme.AppDesign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class MissingLivePriceReport(
    val rows: List<String>,
    val total: Int,
    val noToken: Int,
    val noTick: Int
)

class MainActivity : AppCompatActivity() {

    private val bg = AppDesign.background
    private val panel = AppDesign.surfaceSolid
    private val yellow = AppDesign.accent
    private val white = AppDesign.textPrimary
    private val grey = AppDesign.textMuted
    private val green = AppDesign.positive
    private val red = AppDesign.negative

    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var headerDivider: View
    private lateinit var footerDivider: View
    private lateinit var content: FrameLayout
    private var currentScreen = "Home"
    private var marketTab = "Equity"
    private var marketCategory = ""
    private var mcxCommodity = ""
    private var mcxExpirySymbol = ""
    private var settingsScreen: SettingsScreen? = null
    private val quoteHandler = Handler(Looper.getMainLooper())
    private var quoteRefresh: Runnable? = null
    private val marketListAdapter = MarketListAdapter()
    private var marketTicksJob: Job? = null
    private var dashboardMetricsJob: Job? = null
    private var quoteUpdateJob: Job? = null
    private var marketListVisible = false
    private lateinit var liveViewModel: InstrumentLiveViewModel
    private var isChartFullscreen = false
    private var chartScreen: HeikinAshiChartScreen? = null
    private var dashboardMetricsUpdater: (() -> Unit)? = null
    private val dashboardSubscriptions = ArrayList<InstrumentKey>()
    private var chromeAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        liveViewModel = ViewModelProvider(this)
            .get(InstrumentLiveViewModel::class.java)

        buildApp()
        showScreen("Home")

        // Show the first frame before starting backend/API initialization.
        // The service performs its heavy work on its own worker thread.
        com.sb.backend.services.MarketForegroundService.start(
            applicationContext
        )
    }

    // बैक बटन दबाने पर सीधे होम स्क्रीन पर जाने का लॉजिक
    override fun onBackPressed() {
        if (isChartFullscreen) {
            chartScreen?.setFullscreen(false) ?: setChartFullscreen(false)
            return
        }

        if (currentScreen != "Home") {
            showScreen("Home")
        } else {
            super.onBackPressed()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildApp() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(root)

        header = AppDesign.header(this)

        val menu = makeText("☰", 20f, yellow, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(12), dp(4))
            setOnClickListener { showMenu() }
        }

        val title = makeText("SB BOT", 17f, white, true).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val notification = makeText("♧", 19f, white, false).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                val dialog = AlertDialog.Builder(this@MainActivity)
                    .setTitle("Notifications")
                    .setMessage(
                        "Live alerts will appear here when the backend " +
                            "publishes a verified signal."
                    )
                    .setPositiveButton("OK", null)
                    .create()
                dialog.setOnShowListener { AppDesign.styleDialog(dialog) }
                dialog.show()
            }
        }

        val settings = makeText("⚙", 19f, white, false).apply {
            setPadding(dp(8), dp(4), dp(2), dp(4))
            setOnClickListener { showScreen("Settings") }
        }

        header.addView(menu)
        header.addView(title)
        header.addView(notification)
        header.addView(settings)

        // Main screen content - पूरी स्क्रीन को घेरने के लिए weight 1f दिया गया है
        content = FrameLayout(this).apply {
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(0, dp(3), 0, dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(AppDesign.FOOTER_HEIGHT_DP)
            )
        }

        val navItems = listOf(
            "Home" to "⌂",
            "Signals" to "↗",
            "Paper" to "▤",
            "Positions" to "▣",
            "Market" to "⌁"
        )

        navItems.forEach { item ->
            val nav = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(3), dp(2), dp(3))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { showScreen(item.first) }
            }

            nav.addView(makeText(item.second, 18f, white, true).apply {
                gravity = Gravity.CENTER
            })
            nav.addView(makeText(item.first, 9f, grey, false).apply {
                gravity = Gravity.CENTER
            })
            footer.addView(nav)
        }

        headerDivider = AppDesign.divider(this)
        footerDivider = AppDesign.divider(this)

        root.addView(header)
        root.addView(headerDivider)
        root.addView(content)
        root.addView(footerDivider)
        root.addView(footer)
    }

    private fun setChartFullscreen(fullscreen: Boolean) {
        isChartFullscreen = fullscreen
        setChromeVisible(!fullscreen)
    }

    private fun setChromeVisible(visible: Boolean, animate: Boolean = true) {
        chromeAnimator?.cancel()
        chromeAnimator = null

        val headerTarget = if (visible) dp(AppDesign.HEADER_HEIGHT_DP) else 0
        val footerTarget = if (visible) dp(AppDesign.FOOTER_HEIGHT_DP) else 0

        if (!animate) {
            applyChromeHeights(headerTarget, footerTarget)
            header.alpha = if (visible) 1f else 0f
            footer.alpha = if (visible) 1f else 0f
            header.visibility = if (visible) View.VISIBLE else View.GONE
            footer.visibility = if (visible) View.VISIBLE else View.GONE
            headerDivider.visibility = if (visible) View.VISIBLE else View.GONE
            footerDivider.visibility = if (visible) View.VISIBLE else View.GONE
            return
        }

        if (visible) {
            header.visibility = View.VISIBLE
            footer.visibility = View.VISIBLE
            headerDivider.visibility = View.VISIBLE
            footerDivider.visibility = View.VISIBLE
        }

        val startHeader = header.layoutParams.height.coerceAtLeast(0)
        val startFooter = footer.layoutParams.height.coerceAtLeast(0)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                val headerHeight = (
                    startHeader + (headerTarget - startHeader) * fraction
                    ).toInt()
                val footerHeight = (
                    startFooter + (footerTarget - startFooter) * fraction
                    ).toInt()
                applyChromeHeights(headerHeight, footerHeight)

                val alpha = if (visible) fraction else 1f - fraction
                header.alpha = alpha
                footer.alpha = alpha
                headerDivider.alpha = alpha
                footerDivider.alpha = alpha
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!visible) {
                        header.visibility = View.GONE
                        footer.visibility = View.GONE
                        headerDivider.visibility = View.GONE
                        footerDivider.visibility = View.GONE
                    }
                    if (chromeAnimator === animation) {
                        chromeAnimator = null
                    }
                }
            })
        }
        chromeAnimator = animator
        animator.start()
    }

    private fun applyChromeHeights(headerHeight: Int, footerHeight: Int) {
        header.layoutParams = (header.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                headerHeight
            )
        header.layoutParams.height = headerHeight
        header.requestLayout()

        footer.layoutParams = (footer.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                footerHeight
            )
        footer.layoutParams.height = footerHeight
        footer.requestLayout()
    }

    private fun formatPrice(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format(Locale.US, "%.2f", value)
    }

    private fun formatRupees(value: Double): String {
        val sign = if (value < 0.0) "-" else ""
        val absValue = kotlin.math.abs(value)
        return if (absValue >= 1_000_000_000.0) {
            String.format(Locale.US, "%s₹%.2fB", sign, absValue / 1_000_000_000.0)
        } else if (absValue >= 1_000_000.0) {
            String.format(Locale.US, "%s₹%.2fCr", sign, absValue / 10_000_000.0)
        } else if (absValue >= 1_000.0) {
            String.format(Locale.US, "%s₹%.2fK", sign, absValue / 1_000.0)
        } else {
            String.format(Locale.US, "%s₹%.2f", sign, absValue)
        }
    }

    private fun makeText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun addLabel(
        parent: LinearLayout,
        value: String,
        size: Float = 16f,
        color: Int = white,
        bold: Boolean = false
    ) {
        val textView = makeText(value, size, color, bold)
        textView.setPadding(0, dp(5), 0, dp(5))
        parent.addView(textView)
    }

    private fun addMetricCard(
        parent: LinearLayout,
        heading: String,
        value: String
    ): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.cardBackground(this@MainActivity, 10)
            setPadding(dp(12), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(3), dp(4), dp(3), dp(6))
            }
        }
        addLabel(card, heading, 11f, grey)
        val valueView = makeText(value, 18f, white, true)
        valueView.setPadding(0, dp(5), 0, dp(5))
        card.addView(valueView)
        parent.addView(card)
        return valueView
    }

    private fun openChart(
        symbol: String,
        exchange: String,
        category: String = ""
    ) {
        val resolvedExchange = exchange.ifBlank { inferExchange(symbol) }
        val chartSymbol = resolveInstrumentSymbol(symbol, resolvedExchange)
        val chartCategory = category.ifBlank {
            when (resolvedExchange.toUpperCase(Locale.ROOT)) {
                "MCX" -> "Commodity"
                "BSE" -> "BSE"
                else -> "Market"
            }
        }

        currentScreen = "Chart"
        marketListVisible = false
        stopQuoteRefresh()
        stopMarketTicks()
        marketListAdapter.release()
        content.removeAllViews()
        chartScreen = null

        if (isChartFullscreen) {
            isChartFullscreen = false
        }
        setChromeVisible(true, animate = false)

        val chart = HeikinAshiChartScreen(
            this,
            initialSymbol = chartSymbol,
            instrumentCategory = chartCategory,
            exchange = resolvedExchange,
            viewModel = liveViewModel,
            onFullscreenChanged = { fullscreen ->
                setChartFullscreen(fullscreen)
            }
        )
        chartScreen = chart
        content.addView(
            chart.createView(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun showScreen(screen: String) {
        currentScreen = screen
        marketListVisible = false
        stopQuoteRefresh()
        stopMarketTicks()
        marketListAdapter.release()
        content.removeAllViews()
        chartScreen = null

        if (isChartFullscreen) {
            isChartFullscreen = false
        }
        setChromeVisible(true, animate = false)

        if (screen == "No Live Price") {
            showMissingLivePrices()
            return
        }

        // Stock Search ko ScrollView ke andar mat rakho.
        // Isse search input aur results ko poora available area milega.
        if (screen == "Stock Search") {
            val searchView = StockSearchScreen(
                context = this,
                onOpenScreen = { destination ->
                    showScreen(destination)
                },
                onStockSelected = { stock ->
                    openChart(stock.symbol, stock.exchange, "Market")
                }
            ).createView()

            content.addView(
                searchView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            return
        }

        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        scroll.addView(body)
        content.addView(scroll)

        when (screen) {
            "Home" -> showDashboard(body)
            "Signals" -> showSignals(body)
            "Paper" -> {
                content.removeAllViews()
                content.addView(
                    PaperTradingScreen.create(
                        this,
                        onOpenChart = { symbol, exchange ->
                            openChart(symbol, exchange, "Market")
                        }
                    ),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            "Chart" -> {
                openChart("NIFTY 50", "NSE", "Index")
            }
            "Positions" -> showPositions(body)
            "Market" -> showMarket(body)
            "Settings" -> {
                content.removeAllViews()

                if (settingsScreen == null) {
                    settingsScreen = SettingsScreen(this)
                }

                content.addView(
                    settingsScreen!!.createView(),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            else -> showDashboard(body)
        }
    }

    private fun showDashboard(body: LinearLayout) {
        addLabel(body, "Trading Dashboard", 23f, white, true)
        addLabel(
            body,
            "Live paper account  •  No broker connection",
            12f, yellow
        )

        // Compact metric cards
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val capitalValue = addMetricCard(
            firstRow,
            "Available Capital",
            "—"
        )
        val pnlValue = addMetricCard(
            firstRow,
            "Today's P&L",
            "—"
        )

        val signalsValue = addMetricCard(
            secondRow,
            "Live Signals",
            "—"
        )
        val positionsValue = addMetricCard(
            secondRow,
            "Open Positions",
            "—"
        )

        body.addView(firstRow)
        body.addView(secondRow)

        fun updateMetrics() {
            if (!BackendManager.isInitialized() ||
                dashboardMetricsJob?.isActive == true
            ) {
                return
            }

            dashboardMetricsJob = lifecycleScope.launch {
                val metrics = withContext(Dispatchers.IO) {
                    try {
                        val snapshot = BackendManager.paperEngine().snapshot()
                        val activeSignals = BackendManager.signals().getActive().size
                        snapshot to activeSignals
                    } catch (error: Exception) {
                        android.util.Log.e(
                            "SB_DASHBOARD",
                            "Dashboard metrics query failed",
                            error
                        )
                        null
                    }
                }

                val snapshot = metrics?.first
                val pnl = snapshot?.let {
                    it.realizedPnl + it.unrealizedPnl
                }

                capitalValue.text = snapshot?.let {
                    formatRupees(it.availableBalance)
                } ?: "—"
                pnlValue.text = pnl?.let { formatRupees(it) } ?: "—"
                pnlValue.setTextColor(
                    when {
                        pnl == null -> white
                        pnl >= 0.0 -> green
                        else -> red
                    }
                )
                positionsValue.text =
                    snapshot?.openPositions?.size?.toString() ?: "—"
                signalsValue.text = metrics?.second?.toString() ?: "—"
            }
        }

        updateMetrics()
        dashboardMetricsUpdater = { updateMetrics() }

        addLabel(body, "Quick Access", 18f, yellow, true)
        addActionCard(body, "📈  Live Signals", "Signals")
        addActionCard(body, "📝  Paper Trading", "Paper")
        addActionCard(body, "▣  Open Positions", "Positions")
        addActionCard(body, "⌁  Market Watch", "Market")
        addActionCard(body, "▥  Chart", "Chart")

        addLabel(body, "Market Watch", 18f, yellow, true)
        addLabel(
            body,
            "Live prices connect होने पर स्वतः update होंगे।",
            13f, grey
        )

        val quoteViews = ArrayList<Triple<String, String, TextView>>()

        listOf(
            "NIFTY 50" to "NSE",
            "BANK NIFTY" to "NSE",
            "RELIANCE" to "NSE",
            "GOLD" to "MCX",
            "SILVER" to "MCX"
        ).forEach { item ->
            val symbol = item.first
            val exchange = item.second

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = AppDesign.cardBackground(this@MainActivity, 10)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(6))
                }
            }

            val name = makeText(symbol, 14f, white, true)
            name.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )

            val price = makeText("—", 14f, grey, false)

            row.addView(name)
            row.addView(price)
            row.setOnClickListener {
                openChart(symbol, exchange, if (exchange == "MCX") "Commodity" else "Market")
            }
            body.addView(row)

            quoteViews.add(Triple(symbol, exchange, price))
        }

        subscribeDashboardQuotes(quoteViews)
        startQuoteRefresh(quoteViews)
    }

    private fun subscribeDashboardQuotes(
        views: List<Triple<String, String, TextView>>
    ) {
        views.distinctBy { "${it.second}|${it.first}" }
            .forEach { (symbol, exchange, _) ->
                val key = InstrumentKey(exchange = exchange, symbol = symbol)
                dashboardSubscriptions.add(key)
                MarketFeedRepository.subscribe(key)
            }
    }

    private fun startQuoteRefresh(
        views: List<Triple<String, String, TextView>>
    ) {
        quoteRefresh?.let {
            quoteHandler.removeCallbacks(it)
        }
        quoteRefresh = null

        val runnable = object : Runnable {
            override fun run() {
                updateQuoteViews(views)
                dashboardMetricsUpdater?.invoke()
                quoteHandler.postDelayed(this, 1000L)
            }
        }

        quoteRefresh = runnable
        quoteHandler.post(runnable)
    }

    private fun updateQuoteViews(
        views: List<Triple<String, String, TextView>>
    ) {
        if (!com.sb.backend.core.BackendManager.isInitialized() ||
            quoteUpdateJob?.isActive == true
        ) {
            return
        }

        quoteUpdateJob = lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) {
                val market = try {
                    com.sb.backend.core.BackendManager.market()
                } catch (error: Exception) {
                    android.util.Log.e(
                        "SB_DASHBOARD",
                        "Market repository unavailable",
                        error
                    )
                    return@withContext emptyList<Triple<TextView, String, Int>>()
                }

                views.mapNotNull { item ->
                    val symbol = item.first
                    val exchange = item.second
                    val quote = try {
                        com.sb.backend.services.LiveQuoteCache.get(symbol, exchange)
                            ?: market.getQuote(symbol, exchange)
                    } catch (_: Exception) {
                        try {
                            com.sb.backend.services.LiveQuoteCache.get(symbol, exchange)
                        } catch (_: Exception) {
                            null
                        }
                    } ?: return@mapNotNull null

                    val change = quote.change
                        ?: quote.previousClose?.let { quote.ltp - it }
                        ?: 0.0

                    Triple(
                        item.third,
                        String.format(Locale.US, "₹%.2f", quote.ltp),
                        if (change >= 0.0) green else red
                    )
                }
            }

            updates.forEach { update ->
                update.first.text = update.second
                update.first.setTextColor(update.third)
            }
        }
    }

    private fun stopQuoteRefresh() {
        quoteRefresh?.let {
            quoteHandler.removeCallbacks(it)
        }
        quoteRefresh = null
        dashboardMetricsUpdater = null

        dashboardSubscriptions.forEach { key ->
            MarketFeedRepository.unsubscribe(key)
        }
        dashboardSubscriptions.clear()
    }

    override fun onStart() {
        super.onStart()
        if (marketListVisible) {
            startMarketTicks()
        }
    }

    override fun onStop() {
        // Stop only Activity UI collection.
        // Foreground service and live feed must continue running.
        stopMarketTicks()
        super.onStop()
    }

    override fun onDestroy() {
        chromeAnimator?.cancel()
        chromeAnimator = null
        stopQuoteRefresh()
        stopMarketTicks()
        marketListAdapter.release()

        // Close only Settings UI resources.
        // Do not stop BackendManager or Kotak live feed here.
        settingsScreen?.close()
        settingsScreen = null

        super.onDestroy()
    }

    private fun addActionCard(
        body: LinearLayout,
        label: String,
        destination: String
    ) {
        val row = makeText(label, 15f, white, true).apply {
            background = AppDesign.cardBackground(this@MainActivity, 10)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(8))
            }
            setOnClickListener { showScreen(destination) }
        }
        body.addView(row)
    }

    private fun showSignals(body: LinearLayout) {
        addLabel(body, "Signals", 22f, white, true)
        addLabel(body, "Live signal engine output", 12f, yellow)

        val signals = try {
            if (BackendManager.isInitialized()) {
                BackendManager.signals().getActive()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (signals.isEmpty()) {
            addLabel(body, "No active signals right now.", 13f, grey)
            return
        }

        signals.forEach { signal ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = AppDesign.cardBackground(this@MainActivity, 10)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(8))
                }
            }

            val sideColor = when (signal.signal.name) {
                "BUY" -> green
                "SELL" -> red
                else -> yellow
            }

            card.addView(
                makeText(
                    "${signal.symbol}  •  ${signal.signal.name}",
                    16f,
                    sideColor,
                    true
                )
            )
            card.addView(
                makeText(
                    "${signal.exchange}  •  ${signal.timeframe}  •  " +
                        "${signal.tradingStyle.name}",
                    11f,
                    grey,
                    false
                )
            )
            card.addView(
                makeText(
                    buildString {
                        append("Entry ").append(formatPrice(signal.entryPrice))
                        append("   SL ").append(formatPrice(signal.stopLoss))
                        append("   T1 ").append(formatPrice(signal.target1))
                        append("   T2 ").append(formatPrice(signal.target2))
                    },
                    12f,
                    white,
                    false
                ).apply { setPadding(0, dp(5), 0, 0) }
            )
            card.addView(
                makeText(
                    String.format(
                        Locale.US,
                        "Confidence %.0f%%  •  %s",
                        signal.confidence,
                        signal.status.name
                    ),
                    10f,
                    grey,
                    false
                ).apply { setPadding(0, dp(3), 0, 0) }
            )

            body.addView(card)
            card.setOnClickListener {
                openChart(signal.symbol, signal.exchange, "Market")
            }
        }
    }

    private fun showPositions(body: LinearLayout) {
        addLabel(body, "Open Positions", 22f, white, true)
        addLabel(body, "Live paper engine positions", 12f, yellow)

        val positions = try {
            if (BackendManager.isInitialized()) {
                BackendManager.paperEngine().getPositions(true)
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (positions.isEmpty()) {
            addLabel(body, "No open paper positions.", 13f, grey)
            return
        }

        positions.forEach { position ->
            val sideColor = if (position.side == "BUY") green else red
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = AppDesign.cardBackground(this@MainActivity, 10)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(8))
                }
            }

            card.addView(
                makeText(
                    "${position.symbol}  •  ${position.side} ${position.quantity}",
                    16f,
                    sideColor,
                    true
                )
            )
            card.addView(
                makeText(
                    "Entry ${formatPrice(position.entryPrice)}   " +
                        "LTP ${formatPrice(position.lastPrice)}",
                    12f,
                    white,
                    false
                ).apply { setPadding(0, dp(5), 0, 0) }
            )
            card.addView(
                makeText(
                    "Unrealized P&L ${formatRupees(position.unrealizedPnl)}",
                    13f,
                    if (position.unrealizedPnl >= 0.0) green else red,
                    true
                ).apply { setPadding(0, dp(4), 0, 0) }
            )

            body.addView(card)
            card.setOnClickListener {
                openChart(position.symbol, position.exchange, "Market")
            }
        }
    }

    private fun createMarketTabs(): View {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(8))
        }

        marketTabs().forEach { tab ->
            val selected = marketTab == tab
            val button = Button(this).apply {
                text = tab
                isAllCaps = false
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setTextColor(if (selected) yellow else grey)
                background = AppDesign.roundedBackground(
                    this@MainActivity,
                    if (selected) Color.argb(46, 255, 193, 7) else AppDesign.surface,
                    8
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
                ).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                setOnClickListener {
                    marketTab = tab
                    marketCategory = ""
                    showScreen("Market")
                }
            }
            tabs.addView(button)
        }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                tabs,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun showMarket(body: LinearLayout) {
        if (marketTab == "Commodity" ||
            (marketTab == "F&O" && marketCategory == "Commodity / MCX")
        ) {
            showMcxMarketWatch()
            return
        }

        addLabel(body, "Market Watch", 22f, white, true)
        body.addView(createMarketTabs())

        if (marketTab == "F&O" && marketCategory.isBlank()) {
            addLabel(body, "F&O Categories", 17f, yellow, true)

            listOf(
                "Equity F&O",
                "Index F&O",
                "Commodity / MCX"
            ).forEach { category ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = AppDesign.cardBackground(this@MainActivity, 10)
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(7))
                    }

                    setOnClickListener {
                        if (category == "Commodity / MCX") {
                            marketTab = "Commodity"
                            marketCategory = ""
                        } else {
                            marketCategory = category
                        }
                        showScreen("Market")
                    }
                }

                val name = makeText(category, 15f, white, true)
                name.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )

                row.addView(name)
                row.addView(makeText("›", 22f, yellow, true))
                body.addView(row)
            }

            return
        }

        val categories = marketCategories()

        if (categories.isNotEmpty()) {
            addLabel(
                body,
                if (marketTab == "Equity") "Equity Categories"
                else "$marketTab Categories",
                17f, yellow, true
            )

            categories.forEach { category ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = AppDesign.roundedBackground(
                        this@MainActivity,
                        if (marketCategory == category) {
                            Color.argb(46, 255, 193, 7)
                        } else {
                            AppDesign.surface
                        },
                        10
                    )
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(7))
                    }
                    setOnClickListener {
                        marketCategory = category
                        showScreen("Market")
                    }
                }

                val name = makeText(
                    marketCategoryLabel(category),
                    15f,
                    white,
                    true
                )
                name.layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )

                row.addView(name)
                row.addView(makeText("›", 22f, yellow, true))
                body.addView(row)
            }

            if (marketCategory.isBlank()) {
                addLabel(
                    body,
                    "ऊपर से category चुनें।",
                    13f, grey
                )
                return
            }

            addLabel(
                body,
                "${marketCategoryLabel(marketCategory)} Instruments",
                17f, yellow, true
            )
        }

        val listItems = when (marketTab) {
            "Equity" -> equityMarketItems()
            "Index" -> indexMarketItems()
            "F&O" -> fnoMarketItems()
            "ETFs", "Bonds", "MF", "Other" -> databaseMarketItems()
            else -> emptyList()
        }

        if (listItems.isEmpty()) {
            addLabel(
                body,
                "${marketTab} instruments अभी उपलब्ध नहीं हैं।",
                13f,
                yellow
            )
            return
        }

        showMarketInstruments(listItems)
    }

    private fun marketTabs(): List<String> {
        val repository = repository()
            ?: return listOf(
                "Equity",
                "Index",
                "F&O",
                "ETFs",
                "Bonds",
                "MF",
                "Other",
                "Commodity"
            )

        return try {
            buildList {
                add("Equity")
                add("Index")
                add("F&O")
                if (repository.countByCategory("ETF") > 0) add("ETFs")
                if (repository.countByCategory("BOND") > 0) add("Bonds")
                if (repository.countByCategory("MUTUAL_FUND") > 0) add("MF")
                if (repository.countByCategory("OTHER") > 0) add("Other")
                add("Commodity")
            }
        } catch (_: Exception) {
            listOf(
                "Equity",
                "Index",
                "F&O",
                "ETFs",
                "Bonds",
                "MF",
                "Other",
                "Commodity"
            )
        }
    }

    private fun marketCategories(): List<String> {
        return when (marketTab) {
            "Equity" -> listOf(
                "NSE_CASH",
                "NSE_SME",
                "NSE_T2T",
                "NSE_SME_T2T",
                "NSE_T2T_SURVEILLANCE",
                "BSE"
            )
            "Index" -> listOf(
                "NIFTY 50",
                "BANK NIFTY",
                "FINNIFTY",
                "MIDCPNIFTY",
                "SENSEX",
                "OTHER INDICES"
            )
            "Bonds" -> listOf("SDL", "NCD", "GSEC", "TBILL", "SGB", "FCD")
            "MF" -> listOf("NSE_MF", "SIF")
            "Other" -> listOf("INVIT", "REIT", "RIGHTS", "WARRANT")
            else -> emptyList()
        }
    }

    private fun marketCategoryLabel(category: String): String {
        return category.split('_').joinToString(" ") { part ->
            when (part) {
                "NSE", "SME", "T2T", "SDL", "NCD", "GSEC",
                "SGB", "FCD", "MF", "SIF", "REIT" -> part
                "TBILL" -> "T-Bill"
                "INVIT" -> "InvIT"
                else -> part
                    .lowercase(Locale.ROOT)
                    .replaceFirstChar { char ->
                        if (char.isLowerCase()) {
                            char.titlecase(Locale.ROOT)
                        } else {
                            char.toString()
                        }
                    }
            }
        }
    }

    private fun equityMarketItems(): List<MarketListItem> {
        if (marketCategory.equals("BSE", ignoreCase = true)) {
            return BseEquityStockMaster.stocks.map { stock ->
                MarketListItem(
                    key = InstrumentKey("BSE", stock.symbol),
                    title = stock.symbol,
                    subtitle = stock.name
                )
            }
        }

        val instruments = databaseInstruments(
            category = "EQUITY",
            subcategory = marketCategory,
            exchange = "NSE"
        )

        return instruments.map(::toMarketListItem)
    }

    private fun indexMarketItems(): List<MarketListItem> {
        if (marketCategory.equals("OTHER INDICES", ignoreCase = true)) {
            return databaseInstruments(category = "INDEX")
                .map(::toMarketListItem)
        }

        val items = ArrayList<MarketListItem>()
        val indexInstrument = repository()?.findBySymbol(
            marketCategory,
            "NSE"
        )

        if (indexInstrument != null) {
            items.add(
                MarketListItem(
                    key = InstrumentKey(
                        indexInstrument.exchange,
                        indexInstrument.symbol
                    ),
                    title = indexInstrument.symbol,
                    subtitle = "Index • " +
                        (indexInstrument.feedName ?: "live feed")
                )
            )
        }

        val constituents: List<Pair<String, String>> = when (marketCategory) {
            "NIFTY 50" -> Nifty50StockMaster.allStocks.map {
                it.first to it.second
            }
            "BANK NIFTY" -> BankNiftyStockMaster.allStocks.map {
                it.first to it.second
            }
            "FINNIFTY" -> FinniftyStockMaster.allStocks.map {
                it.first to it.second
            }
            else -> emptyList()
        }

        items.addAll(constituentItems(constituents))
        return items
    }

    private fun fnoMarketItems(): List<MarketListItem> {
        val symbols = when (marketCategory) {
            "Index F&O" -> listOf(
                "NIFTY — Nifty 50",
                "BANKNIFTY — Bank Nifty",
                "FINNIFTY — Nifty Financial Services",
                "MIDCPNIFTY — Nifty Midcap Select"
            )
            "Equity F&O" -> listOf(
                "RELIANCE — Reliance Industries Limited",
                "TCS — Tata Consultancy Services Limited",
                "HDFCBANK — HDFC Bank Limited",
                "INFY — Infosys Limited",
                "ICICIBANK — ICICI Bank Limited",
                "SBIN — State Bank of India",
                "TATAMOTORS — Tata Motors Limited",
                "TATASTEEL — Tata Steel Limited",
                "AXISBANK — Axis Bank Limited",
                "BHARTIARTL — Bharti Airtel Limited",
                "ITC — ITC Limited",
                "LT — Larsen & Toubro Limited",
                "BAJFINANCE — Bajaj Finance Limited",
                "MARUTI — Maruti Suzuki India Limited",
                "SUNPHARMA — Sun Pharmaceutical Industries Limited",
                "NTPC — NTPC Limited",
                "POWERGRID — Power Grid Corporation of India Limited",
                "ONGC — Oil & Natural Gas Corporation Limited",
                "COALINDIA — Coal India Limited",
                "TITAN — Titan Company Limited",
                "WIPRO — Wipro Limited",
                "ADANIENT — Adani Enterprises Limited",
                "ADANIPORTS — Adani Ports and SEZ Limited",
                "ZOMATO — Zomato Limited",
                "JSWSTEEL — JSW Steel Limited"
            )
            else -> emptyList()
        }

        return symbols.map { symbol ->
            val selected = symbol.substringBefore(" — ").trim()
            val liveSymbol = resolveLiveSymbol(
                selected,
                marketTab,
                marketCategory
            )

            MarketListItem(
                key = InstrumentKey("NSE", liveSymbol),
                title = selected,
                subtitle = symbol.substringAfter(" — ", "").trim()
            )
        }
    }

    private fun databaseMarketItems(): List<MarketListItem> {
        val category = when (marketTab) {
            "ETFs" -> "ETF"
            "Bonds" -> "BOND"
            "MF" -> "MUTUAL_FUND"
            "Other" -> "OTHER"
            else -> marketTab
        }

        return databaseInstruments(
            category = category,
            subcategory = marketCategory
        ).map(::toMarketListItem)
    }

    private fun constituentItems(
        constituents: List<Pair<String, String>>
    ): List<MarketListItem> {
        val repository = repository()
        return constituents.mapNotNull { (symbol, name) ->
            val instrument = repository?.findBySymbol(symbol, "NSE")
            if (instrument != null) {
                MarketListItem(
                    key = InstrumentKey(
                        instrument.exchange,
                        instrument.symbol
                    ),
                    title = instrument.symbol,
                    subtitle = instrument.name
                )
            } else {
                MarketListItem(
                    key = InstrumentKey("NSE", symbol),
                    title = symbol,
                    subtitle = name
                )
            }
        }
    }

    private fun databaseInstruments(
        category: String,
        subcategory: String? = null,
        exchange: String? = null
    ): List<Instrument> {
        return try {
            repository()?.getByCategory(
                category = category,
                subcategory = subcategory,
                exchange = exchange,
                limit = 10000
            ).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun toMarketListItem(instrument: Instrument): MarketListItem {
        val subtitle = buildString {
            append(instrument.name)
            if (instrument.subcategory.isNotBlank() &&
                !instrument.subcategory.equals(instrument.category, true)
            ) {
                append(" • ")
                append(marketCategoryLabel(instrument.subcategory))
            }
            if (instrument.isin?.isNotBlank() == true) {
                append(" • ")
                append(instrument.isin)
            }
        }
        return MarketListItem(
            key = InstrumentKey(instrument.exchange, instrument.symbol),
            title = instrument.symbol,
            subtitle = subtitle
        )
    }

    private fun repository(): InstrumentRepository? {
        return try {
            if (BackendManager.isInitialized()) {
                BackendManager.instruments()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun showMcxMarketWatch() {
        val contracts = loadMcxContracts()

        val grouped = contracts
            .filter {
                it.expiryMillis != null &&
                    !it.token.isNullOrBlank() &&
                    it.name.isNotBlank()
            }
            .groupBy {
                it.name.trim().toUpperCase(Locale.ROOT)
            }
            .filterValues { it.isNotEmpty() }
            .toSortedMap()

        if (grouped.isEmpty()) {
            showMarketInstruments(emptyList(), emptyMap())
            return
        }

        if (!grouped.containsKey(mcxCommodity)) {
            mcxCommodity = if (grouped.containsKey("GOLD")) {
                "GOLD"
            } else {
                grouped.keys.first()
            }
        }

        val commodityContracts = grouped[mcxCommodity]
            .orEmpty()
            .distinctBy { it.symbol }
            .sortedBy { it.expiryMillis ?: Long.MAX_VALUE }
            .take(3)

        if (commodityContracts.none { it.symbol == mcxExpirySymbol }) {
            mcxExpirySymbol = commodityContracts
                .firstOrNull()
                ?.symbol
                .orEmpty()
        }

        val selected = commodityContracts.firstOrNull {
            it.symbol == mcxExpirySymbol
        }

        val items = if (selected == null) {
            emptyList()
        } else {
            listOf(
                MarketListItem(
                    key = InstrumentKey("MCX", selected.symbol),
                    title = selected.symbol,
                    subtitle = "${mcxCommodityLabel(selected.name)} • " +
                        "${formatMcxExpiry(selected.expiryMillis)} • " +
                        "Token ${selected.token.orEmpty()}"
                )
            )
        }

        showMarketInstruments(items, grouped)
    }

    // Backend initialization is owned by MarketForegroundService.
    // Activity only reads MCX contracts after BackendManager is ready.
    private fun loadMcxContracts(): List<Instrument> {
        return try {
            if (!BackendManager.isReady()) {
                android.util.Log.d(
                    "MainActivity",
                    "Backend is not ready; MCX contracts unavailable yet"
                )
                return emptyList()
            }

            BackendManager.instruments().getActiveMcxFutures()

        } catch (e: Exception) {
            android.util.Log.w(
                "MainActivity",
                "MCX contracts load failed",
                e
            )
            emptyList()
        }
    }

    private fun createMcxSelectors(
        grouped: Map<String, List<Instrument>>
    ): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val commodityContracts = grouped[mcxCommodity]
            .orEmpty()
            .distinctBy { it.symbol }
            .sortedBy { it.expiryMillis ?: Long.MAX_VALUE }
            .take(3)

        val selected = commodityContracts.firstOrNull {
            it.symbol == mcxExpirySymbol
        }

        container.addView(
            createMcxSelector(
                "Commodity",
                mcxCommodityLabel(mcxCommodity)
            ) {
                showMcxCommodityPicker(grouped)
            }
        )

        container.addView(
            createMcxSelector(
                "Expiry",
                if (selected == null) {
                    "No Contract"
                } else {
                    "${selected.symbol} • " +
                        formatMcxExpiry(selected.expiryMillis)
                }
            ) {
                showMcxExpiryPicker(commodityContracts)
            }
        )

        return container
    }

    private fun createMcxSelector(
        label: String,
        value: String,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppDesign.cardBackground(this@MainActivity, 10)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(6))
            }
            setOnClickListener { onClick() }
        }

        row.addView(makeText(label, 12f, grey, false))

        val valueView = makeText(value, 13f, white, true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        row.addView(valueView)
        row.addView(makeText("▾", 14f, yellow, true))

        return row
    }

    private fun showMcxCommodityPicker(
        grouped: Map<String, List<Instrument>>
    ) {
        val codes = grouped.keys.toList()

        if (codes.isEmpty()) return

        val labels = codes.map { mcxCommodityLabel(it) }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Commodity")
            .setItems(labels) { _, which ->
                mcxCommodity = codes[which]
                mcxExpirySymbol = ""
                showScreen("Market")
            }
            .create()

        dialog.setOnShowListener { AppDesign.styleDialog(dialog) }
        dialog.show()
    }

    private fun showMcxExpiryPicker(contracts: List<Instrument>) {
        if (contracts.isEmpty()) return

        val labels = contracts.map {
            "${it.symbol} • ${formatMcxExpiry(it.expiryMillis)}"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select Expiry")
            .setItems(labels) { _, which ->
                mcxExpirySymbol = contracts[which].symbol
                showScreen("Market")
            }
            .create()

        dialog.setOnShowListener { AppDesign.styleDialog(dialog) }
        dialog.show()
    }

    private fun formatMcxExpiry(millis: Long?): String {
        if (millis == null || millis <= 0L) return "No Expiry"

        return SimpleDateFormat("dd MMM yy", Locale.US)
            .format(Date(millis))
    }

    private fun mcxCommodityLabel(code: String): String {
        return when (code.trim().toUpperCase(Locale.ROOT)) {
            "ALUMINI" -> "ALUMINIUM MINI"
            "COTTONOIL" -> "COTTON OIL"
            "CRUDEOIL" -> "CRUDE OIL"
            "CRUDEOILM" -> "CRUDE OIL MINI"
            "ELECDMBL" -> "ELECTRICITY"
            "GOLDGUINEA" -> "GOLD GUINEA"
            "GOLDM" -> "GOLD MINI"
            "GOLDPETAL" -> "GOLD PETAL"
            "GOLDTEN" -> "GOLD TEN"
            "LEADMINI" -> "LEAD MINI"
            "MENTHAOIL" -> "MENTHA OIL"
            "NATGASMINI" -> "NATURAL GAS MINI"
            "NATURALGAS" -> "NATURAL GAS"
            "SILVER100" -> "SILVER 100"
            "SILVERM" -> "SILVER M"
            "SILVERMIC" -> "SILVER MICRO"
            "STEELREBAR" -> "STEEL REBAR"
            "SUNOIL" -> "SUNFLOWER OIL"
            "ZINCMINI" -> "ZINC MINI"
            else -> code.trim().toUpperCase(Locale.ROOT)
        }
    }

    private fun showMarketInstruments(
        items: List<MarketListItem>,
        mcxGroups: Map<String, List<Instrument>> = emptyMap()
    ) {
        val isMcx = marketTab == "Commodity" ||
            (marketTab == "F&O" && marketCategory == "Commodity / MCX")

        marketListVisible = true
        content.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(12), dp(12), dp(12), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        root.addView(makeText("Market Watch", 22f, white, true))
        root.addView(createMarketTabs())

        if (isMcx && mcxGroups.isNotEmpty()) {
            root.addView(createMcxSelectors(mcxGroups))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val heading = if (marketCategory.isBlank()) {
            marketTab
        } else {
            "$marketCategory Instruments"
        }

        val headingView = makeText(heading, 15f, yellow, true)
        headingView.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        header.addView(headingView)

        val missing = makeText("No LTP", 12f, yellow, false).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                showScreen("No Live Price")
            }
        }
        header.addView(missing)

        if (marketCategory.isNotBlank()) {
            val back = makeText("‹ Categories", 12f, grey, false).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener {
                    marketCategory = ""
                    showScreen("Market")
                }
            }
            header.addView(back)
        }

        root.addView(header)

        if (isMcx && items.isEmpty()) {
            root.addView(
                makeText(
                    "No Contract Available. Settings → Sync Kotak " +
                        "Scrip Master.",
                    13f,
                    yellow,
                    false
                ).apply {
                    setPadding(0, dp(4), 0, dp(10))
                }
            )
        }

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setHasFixedSize(true)
            itemAnimator = null
            setBackgroundColor(bg)
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            adapter = marketListAdapter
        }

        root.addView(
            recycler,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        content.addView(root)

        marketListAdapter.onItemClick = { item ->
            val category = marketCategory.ifBlank { marketTab }
            marketListVisible = false
            openChart(item.key.symbol, item.key.exchange, category)
        }

        marketListAdapter.submitItems(items)
        startMarketTicks()
    }

    private fun showMissingLivePrices() {
        marketListVisible = false
        stopMarketTicks()
        marketListAdapter.release()
        content.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = makeText("No Live Price", 17f, white, true)
        title.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        header.addView(title)

        val back = makeText("‹ Market", 12f, yellow, false).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { showScreen("Market") }
        }
        header.addView(back)

        val refresh = makeText("Refresh", 12f, grey, false).apply {
            setPadding(dp(8), dp(6), 0, dp(6))
            setOnClickListener { showMissingLivePrices() }
        }
        header.addView(refresh)

        root.addView(header)

        val summary = makeText("Scanning instruments...", 11f, grey, false)
        summary.setPadding(0, dp(4), 0, dp(6))
        root.addView(summary)

        val list = ListView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            divider = android.graphics.drawable.ColorDrawable(AppDesign.divider)
            dividerHeight = 1
        }
        root.addView(
            list,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        content.addView(root)

        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) {
                buildMissingLivePriceReport()
            }

            if (currentScreen != "No Live Price") {
                return@launch
            }

            summary.text = buildString {
                append("Missing: ").append(report.total)
                append("  •  No token: ").append(report.noToken)
                append("  •  No tick: ").append(report.noTick)
                if (report.total > report.rows.size) {
                    append("  •  Showing first ")
                    append(report.rows.size)
                }
            }

            list.adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_list_item_1,
                report.rows.ifEmpty {
                    listOf("All active instruments have a live price.")
                }
            )
        }
    }

    private fun buildMissingLivePriceReport(): MissingLivePriceReport {
        if (!BackendManager.isInitialized()) {
            return MissingLivePriceReport(
                rows = listOf("Backend is not initialized yet."),
                total = 0,
                noToken = 0,
                noTick = 0
            )
        }

        val instruments = try {
            BackendManager.instruments().getActive(5000)
        } catch (_: Exception) {
            emptyList()
        }

        if (instruments.isEmpty()) {
            return MissingLivePriceReport(
                rows = listOf("No active instruments found. Sync scrip master."),
                total = 0,
                noToken = 0,
                noTick = 0
            )
        }

        val rows = ArrayList<String>()
        var noToken = 0
        var noTick = 0

        instruments.forEach { instrument ->
            val key = InstrumentKey(
                exchange = instrument.exchange,
                symbol = instrument.symbol
            )

            if (MarketDataManager.getTick(key) != null) {
                return@forEach
            }

            if (instrument.segment.equals("COMMODITY", true) &&
                instrument.expiryMillis == null
            ) {
                // Base commodity rows are templates, not tradeable contracts.
                return@forEach
            }

            if (instrument.segment.equals("INDEX", true)) {
                // Index subscriptions use feed names, not Kotak tokens.
                noTick++
                if (rows.size < MAX_MISSING_LIVE_PRICE_ROWS) {
                    rows.add(
                        "${instrument.symbol}  •  ${instrument.exchange}" +
                            "  —  index feed waiting for tick"
                    )
                }
                return@forEach
            }

            if (instrument.token.isNullOrBlank()) {
                noToken++
                if (rows.size < MAX_MISSING_LIVE_PRICE_ROWS) {
                    rows.add(
                        "${instrument.symbol}  •  ${instrument.exchange}" +
                            "  —  no Kotak token"
                    )
                }
                return@forEach
            }

            noTick++
            if (rows.size < MAX_MISSING_LIVE_PRICE_ROWS) {
                val reason = if (MarketDataManager.isSubscribed(key)) {
                    "subscribed, waiting for tick"
                } else {
                    "no live tick received"
                }
                rows.add(
                    "${instrument.symbol}  •  ${instrument.exchange}" +
                        "  —  $reason"
                )
            }
        }

        return MissingLivePriceReport(
            rows = rows,
            total = noToken + noTick,
            noToken = noToken,
            noTick = noTick
        )
    }

    private fun startMarketTicks() {
        stopMarketTicks()

        marketListAdapter.refreshFromSnapshot()

        marketTicksJob = lifecycleScope.launch {
            launch {
                MarketDataManager.tickStream
                    .conflate()
                    .collect { tick ->
                        marketListAdapter.onTick(tick)
                    }
            }

            launch {
                MarketDataManager.connectionState.collect { state ->
                    if (state ==
                        MarketDataManager.ConnectionState.AUTHENTICATED
                    ) {
                        marketListAdapter.resubscribeVisible()
                    }
                }
            }
        }
    }

    private fun stopMarketTicks() {
        marketTicksJob?.cancel()
        marketTicksJob = null
    }

    private fun inferExchange(symbol: String): String {
        val normalizedSymbol = symbol.trim()
        if (normalizedSymbol.isEmpty()) return "NSE"
        return try {
            if (BackendManager.isInitialized()) {
                BackendManager.instruments()
                    .findBySymbol(normalizedSymbol)
                    ?.exchange ?: "NSE"
            } else {
                "NSE"
            }
        } catch (_: Exception) {
            "NSE"
        }
    }

    private fun resolveLiveExchange(
        tab: String,
        category: String
    ): String {
        return when {
            tab == "F&O" && category == "Commodity / MCX" -> "MCX"
            tab == "Equity" && category == "BSE" -> "BSE"
            tab == "Equity" -> "NSE"
            tab == "Index" -> "NSE"
            tab == "ETFs" -> "NSE"
            tab == "F&O" -> "NSE"
            else -> "MCX"
        }
    }

    private fun resolveLiveSymbol(
        symbol: String,
        tab: String,
        category: String
    ): String {
        if (tab == "F&O" && category == "Index F&O") {
            return when (symbol.toUpperCase(Locale.ROOT)) {
                "NIFTY" -> "NIFTY 50"
                "BANKNIFTY" -> "BANK NIFTY"
                "FINNIFTY" -> "FINNIFTY"
                "MIDCPNIFTY" -> "MIDCPNIFTY"
                else -> symbol
            }
        }

        if (tab == "F&O" && category == "Commodity / MCX") {
            return when (symbol.toUpperCase(Locale.ROOT)) {
                "CRUDEOIL" -> "CRUDE OIL"
                "CRUDEOILM" -> "CRUDE OIL MINI"
                "NATURALGAS" -> "NATURAL GAS"
                "NATGASMINI" -> "NATURAL GAS MINI"
                "MENTHAOIL" -> "MENTHA OIL"
                "SILVER M" -> "SILVER MINI"
                "COPPER MINI" -> "COPPER"
                "ALUMINIUM MINI" -> "ALUMINIUM"
                "LEAD" -> "LEAD MINI"
                else -> symbol
            }
        }

        return symbol
    }

    private fun resolveInstrumentSymbol(
        symbol: String,
        exchange: String
    ): String {
        return try {
            if (com.sb.backend.core.BackendManager.isInitialized()) {
                com.sb.backend.core.BackendManager.instruments()
                    .findBySymbol(symbol, exchange)
                    ?.symbol
                    ?: symbol
            } else {
                symbol
            }
        } catch (_: Exception) {
            symbol
        }
    }

    private fun showMenu() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val screenWidth = resources.displayMetrics.widthPixels
        val minWidth = dp(220)
        val maxWidth = (screenWidth * 0.90f).toInt()
        var drawerWidth = (screenWidth * 0.78f).toInt()
            .coerceIn(minWidth, maxWidth)

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(AppDesign.scrim)
            setOnClickListener { dialog.dismiss() }
        }

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.overlayBackground(this@MainActivity, 0)
            elevation = dp(10).toFloat()
            isClickable = true
        }

        val drawerParams = FrameLayout.LayoutParams(
            drawerWidth,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START
        )
        overlay.addView(drawer, drawerParams)

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(18),
                dp(14),
                dp(10),
                dp(14)
            )
            setBackgroundColor(Color.TRANSPARENT)
        }

        val title = makeText(
            "SB BOT",
            19f,
            white,
            true
        )
        title.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )

        val close = makeText("✕", 24f, yellow, true).apply {
            gravity = Gravity.CENTER
            setPadding(
                dp(12),
                dp(8),
                dp(8),
                dp(8)
            )
            setOnClickListener { dialog.dismiss() }
        }

        header.addView(title)
        header.addView(close)
        drawer.addView(header)

        val status = makeText(
            "MENU  •  NOT VERIFIED",
            10f,
            yellow,
            true
        ).apply {
            setPadding(
                dp(18),
                dp(10),
                dp(12),
                dp(8)
            )
        }
        drawer.addView(status)

        val menuItems = listOf(
            "Home" to "⌂",
            "Signals" to "↗",
            "Paper" to "▤",
            "Positions" to "▣",
            "Market" to "⌁",
            "No Live Price" to "⚠",
            "Chart" to "▥",
            "Stock Search" to "⌕",
            "Settings" to "⚙"
        )

        menuItems.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    dp(18),
                    dp(12),
                    dp(12),
                    dp(12)
                )
                setBackgroundColor(
                    if (currentScreen == item.first) {
                        Color.argb(42, 255, 193, 7)
                    } else {
                        Color.TRANSPARENT
                    }
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    showScreen(item.first)
                }
            }

            val icon = makeText(
                item.second,
                18f,
                yellow,
                true
            )
            icon.gravity = Gravity.CENTER
            icon.layoutParams = LinearLayout.LayoutParams(
                dp(34),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val label = makeText(
                item.first,
                14f,
                white,
                currentScreen == item.first
            )
            label.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )

            val arrow = makeText(
                "›",
                18f,
                grey,
                false
            )

            row.addView(icon)
            row.addView(label)
            row.addView(arrow)
            drawer.addView(row)
        }

        // Flexible empty space
        drawer.addView(
            android.view.View(this),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // Draggable resize handle
        val handle = android.view.View(this).apply {
            setBackgroundColor(yellow)
            contentDescription = "Drag to resize menu"
        }

        drawer.addView(
            handle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(8)
            )
        )

        var startX = 0f
        var startWidth = drawerWidth

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidth = drawerParams.width
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    drawerWidth = (
                        startWidth + event.rawX - startX
                    ).toInt().coerceIn(minWidth, maxWidth)

                    drawerParams.width = drawerWidth
                    drawer.layoutParams = drawerParams
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        dialog.setContentView(overlay)
        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            setGravity(Gravity.START or Gravity.TOP)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.25f)
        }

        dialog.show()

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private companion object {
        const val MAX_MISSING_LIVE_PRICE_ROWS = 2000
    }
}
