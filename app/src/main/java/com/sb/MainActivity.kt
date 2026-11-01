package com.sb

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(18, 18, 18)
    private val panel = Color.rgb(30, 30, 30)
    private val yellow = Color.rgb(255, 193, 7)
    private val white = Color.WHITE
    private val grey = Color.rgb(160, 160, 160)
    private val green = Color.rgb(0, 200, 83)
    private val red = Color.rgb(255, 82, 82)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private var currentScreen = "Home"
    private var marketTab = "Equity"
    private var marketCategory = ""
    private lateinit var dbHelper: DashboardDbHelper
    private lateinit var stockMasterDbHelper: StockMasterDbHelper
    private lateinit var sbApiServer: com.sb.backend.server.EmbeddedApiServer
    private var settingsScreen: SettingsScreen? = null
    private val quoteHandler = Handler(Looper.getMainLooper())
    private var quoteRefresh: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        dbHelper = DashboardDbHelper(this)
        stockMasterDbHelper = StockMasterDbHelper(this)
        buildApp()

        sbApiServer = com.sb.backend.server.EmbeddedApiServer(10000)

        val serverStarted = sbApiServer.start()

        android.util.Log.d(
            "SB_API_SERVER",
            "Backend Server Started: $serverStarted"
        )

        val backendManager =
            com.sb.backend.services.BackendManager(this)

        val backendReady = backendManager.initialize()

        android.util.Log.d(
            "SB_BACKEND_INIT",
            "Backend Initialized: $backendReady"
        )

        android.util.Log.d(
            "SB_BACKEND_INIT",
            "Database Status: ${backendManager.getDatabaseStatus()}"
        )

        val databaseTest = com.sb.backend.database.BackendDatabaseTest(this)
        val result = databaseTest.testConnection()
        android.util.Log.d("SB_BACKEND_TEST", result)

        val instrumentTest =
            com.sb.backend.services.InstrumentFunctionalTest(
                backendManager.instrumentRepository
            )

        android.util.Log.d(
            "SB_INSTRUMENT_TEST",
            instrumentTest.runTest()
        )

        val marketTest =
            com.sb.backend.services.MarketFunctionalTest(
                backendManager.marketRepository
            )

        android.util.Log.d(
            "SB_MARKET_TEST",
            marketTest.runTest()
        )

        com.sb.backend.core.BackendManager.initialize(applicationContext)

        com.sb.backend.core.BackendManager.start(
            seedBuiltInMasters = true,
            onReady = {
                com.sb.backend.services.KotakSessionCoordinator.initialize(
                    applicationContext
                )
                com.sb.backend.services.KotakSessionCoordinator.startIfEnabled()

                if (com.sb.backend.core.BackendManager.isReady()) {
                    val exportUri =
                        com.sb.backend.core.BackendManager.exportDatabaseToDownloads()

                    android.util.Log.d(
                        "SB_DATABASE_EXPORT",
                        "Database Export URI: $exportUri"
                    )
                } else {
                    android.util.Log.e(
                        "SB_DATABASE_EXPORT",
                        "Backend initialization or seeding failed"
                    )
                }
            }
        )

        showScreen("Home")
    }

    // बैक बटन दबाने पर सीधे होम स्क्रीन पर जाने का लॉजिक
    override fun onBackPressed() {
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

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(panel)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val menu = makeText("☰", 23f, yellow, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(12), dp(4))
            setOnClickListener { showMenu() }
        }

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleBlock.addView(makeText("SB BOT", 20f, white, true))
        titleBlock.addView(
            makeText("● Not verified", 11f, yellow, false)
        )

        val notification = makeText("♧", 22f, white, false).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Notifications")
                    .setMessage("अभी कोई verified notification उपलब्ध नहीं है।")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        val settings = makeText("⚙", 22f, white, false).apply {
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { showScreen("Settings") }
        }

        header.addView(menu)
        header.addView(titleBlock)
        header.addView(notification)
        header.addView(settings)

        // Main screen content - पूरी स्क्रीन को घेरने के लिए weight 1f दिया गया है
        content = FrameLayout(this).apply {
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // Fixed five-button footer
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(panel)
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
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
                setPadding(dp(2), dp(6), dp(2), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { showScreen(item.first) }
            }

            nav.addView(makeText(item.second, 20f, white, true).apply {
                gravity = Gravity.CENTER
            })
            nav.addView(makeText(item.first, 10f, white, false).apply {
                gravity = Gravity.CENTER
            })
            footer.addView(nav)
        }

        root.addView(header)
        root.addView(content)
        root.addView(footer)
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

    private fun addCard(parent: LinearLayout, heading: String, value: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }
        addLabel(card, heading, 13f, grey)
        addLabel(card, value, 21f, white, true)
        parent.addView(card)
    }

    private fun addMetricCard(
        parent: LinearLayout,
        heading: String,
        value: String
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(dp(12), dp(12), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(dp(3), dp(4), dp(3), dp(6))
            }
        }
        addLabel(card, heading, 12f, grey)
        addLabel(card, value, 19f, white, true)
        parent.addView(card)
    }

    private fun showScreen(screen: String) {
        currentScreen = screen
        stopQuoteRefresh()
        content.removeAllViews()

        // Stock Search ko ScrollView ke andar mat rakho.
        // Isse search input aur results ko poora available area milega.
        if (screen == "Stock Search") {
            val searchView = StockSearchScreen(
                context = this,
                onOpenScreen = { destination ->
                    showScreen(destination)
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
                    PaperTradingScreen.create(this),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            "Chart" -> {
                content.removeAllViews()
                content.addView(
                    HeikinAshiChartScreen(this).createView()
                )
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
        addLabel(body, "Account overview  •  Not verified", 12f, yellow)

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

        addMetricCard(
            firstRow,
            "Available Capital",
            dbHelper.getValue("capital") ?: "—"
        )
        addMetricCard(
            firstRow,
            "Today's P&L",
            dbHelper.getValue("pnl") ?: "—"
        )

        addMetricCard(
            secondRow,
            "Live Signals",
            dbHelper.getValue("signals") ?: "—"
        )
        addMetricCard(
            secondRow,
            "Open Positions",
            dbHelper.getValue("positions") ?: "—"
        )

        body.addView(firstRow)
        body.addView(secondRow)

        addLabel(body, "Dashboard Data", 18f, yellow, true)
        body.addView(createDashboardDataEntry())

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
                setBackgroundColor(panel)
                setPadding(dp(14), dp(12), dp(14), dp(12))
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
            body.addView(row)

            quoteViews.add(Triple(symbol, exchange, price))
        }

        startQuoteRefresh(quoteViews)
    }

    private fun startQuoteRefresh(
        views: List<Triple<String, String, TextView>>
    ) {
        stopQuoteRefresh()

        val runnable = object : Runnable {
            override fun run() {
                updateQuoteViews(views)
                quoteHandler.postDelayed(this, 1000L)
            }
        }

        quoteRefresh = runnable
        quoteHandler.post(runnable)
    }

    private fun updateQuoteViews(
        views: List<Triple<String, String, TextView>>
    ) {
        if (!com.sb.backend.core.BackendManager.isInitialized()) {
            return
        }

        val market = com.sb.backend.core.BackendManager.market()

        views.forEach { item ->
            val symbol = item.first
            val exchange = item.second
            val view = item.third

            val quote = try {
                com.sb.backend.services.LiveQuoteCache.get(symbol, exchange)
                    ?: market.getQuote(symbol, exchange)
            } catch (_: Exception) {
                try {
                    com.sb.backend.services.LiveQuoteCache.get(symbol, exchange)
                } catch (_: Exception) {
                    null
                }
            } ?: return@forEach

            view.text = String.format(
                Locale.US,
                "₹%.2f",
                quote.ltp
            )

            val change = quote.change
                ?: quote.previousClose?.let {
                    quote.ltp - it
                }
                ?: 0.0

            view.setTextColor(if (change >= 0.0) green else red)
        }
    }

    private fun stopQuoteRefresh() {
        quoteRefresh?.let {
            quoteHandler.removeCallbacks(it)
        }
        quoteRefresh = null
    }

    override fun onDestroy() {
        stopQuoteRefresh()
        settingsScreen?.close()
        settingsScreen = null
        super.onDestroy()
    }

    private fun addActionCard(
        body: LinearLayout,
        label: String,
        destination: String
    ) {
        val row = makeText(label, 16f, white, true).apply {
            setBackgroundColor(panel)
            setPadding(dp(16), dp(16), dp(16), dp(16))
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
        addLabel(body, "BUY / SELL / WAIT", 14f, yellow, true)
        addCard(body, "Live Signal Feed", "No verified live signals")
        addLabel(
            body,
            "Backend/API connection verify होने के बाद ही वास्तविक signals दिखेंगे।",
            13f, grey
        )
    }

    private fun showPaper(body: LinearLayout) {
        // Replaced by PaperTradingScreen.create() to avoid mock data.
    }

    private fun showPositions(body: LinearLayout) {
        addLabel(body, "Open Positions", 22f, white, true)
        addCard(body, "Active Positions", "—")
        addLabel(body, "अभी verified position data उपलब्ध नहीं है।", 13f, grey)
    }

    private fun showMarket(body: LinearLayout) {
        addLabel(body, "Market Watch", 22f, white, true)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(12))
        }

        listOf("Equity", "Index", "F&O", "ETFs").forEach { tab ->
            val button = Button(this).apply {
                text = tab
                isAllCaps = false
                setTextColor(if (marketTab == tab) yellow else white)
                setBackgroundColor(panel)
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(48), 1f
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
        body.addView(tabs)

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
                    setBackgroundColor(panel)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
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

        val categories: List<String> = when (marketTab) {
            "Equity" -> listOf("NSE", "BSE")
            "Index" -> listOf(
                "NIFTY 50",
                "BANK NIFTY",
                "FINNIFTY",
                "NIFTY 100",
                "NIFTY 200",
                "NIFTY 500",
                "NIFTY NEXT 50",
                "NIFTY MIDCAP 50",
                "NIFTY MIDCAP 100",
                "NIFTY SMALLCAP 100",
                "NIFTY IT",
                "NIFTY AUTO",
                "NIFTY PHARMA",
                "NIFTY FMCG",
                "NIFTY METAL",
                "NIFTY REALTY",
                "NIFTY ENERGY",
                "OTHER INDICES"
            )
            else -> emptyList()
        }

        if (categories.isNotEmpty()) {
            addLabel(
                body,
                if (marketTab == "Equity") "Equity Categories"
                else "Index Categories",
                17f, yellow, true
            )

            categories.forEach { category ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(
                        if (marketCategory == category) {
                            Color.rgb(55, 48, 25)
                        } else panel
                    )
                    setPadding(dp(14), dp(14), dp(14), dp(14))
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

            if (marketCategory.isBlank()) {
                addLabel(
                    body,
                    "ऊपर से category चुनें।",
                    13f, grey
                )
                return
            }

            addLabel(body, "$marketCategory Instruments", 17f, yellow, true)
        }

        val symbols: List<String> = when (marketTab) {
            "Equity" -> when (marketCategory) {
                "NSE" -> EquityStockMaster.allEquities.map {
                    "${it.first} — ${it.second}"
                }

                "BSE" -> BseEquityStockMaster.stocks.map {
                    "${it.symbol} — ${it.name}"
                }

                else -> emptyList()
            }

            "F&O" -> when (marketCategory) {
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

                "Commodity / MCX" -> listOf(
                    "GOLD — Gold",
                    "GOLD MINI — Gold Mini",
                    "GOLD PETAL — Gold Petal",
                    "SILVER — Silver",
                    "SILVER M — Silver Mini",
                    "SILVER MICRO — Silver Micro",
                    "CRUDEOIL — Crude Oil",
                    "CRUDEOILM — Crude Oil Mini",
                    "NATURALGAS — Natural Gas",
                    "NATGASMINI — Natural Gas Mini",
                    "COPPER — Copper",
                    "COPPER MINI — Copper Mini",
                    "ZINC — Zinc",
                    "ZINC MINI — Zinc Mini",
                    "ALUMINIUM — Aluminium",
                    "ALUMINIUM MINI — Aluminium Mini",
                    "LEAD — Lead",
                    "LEAD MINI — Lead Mini",
                    "MENTHAOIL — Mentha Oil",
                    "COTTON — Cotton",
                    "KAPAS — Kapas",
                    "GUARSEED — Guar Seed",
                    "GUARGUM — Guar Gum"
                )

                else -> emptyList()
            }

            "Index" -> when (marketCategory) {
                "NIFTY 50" -> Nifty50StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "BANK NIFTY" -> BankNiftyStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "FINNIFTY" -> FinniftyStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY 100" -> Nifty100StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY 200" -> Nifty200StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY 500" -> Nifty500StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY NEXT 50" -> NiftyNext50StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY MIDCAP 50" -> NiftyMidcap50StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY MIDCAP 100" -> NiftyMidcap100StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY SMALLCAP 100" -> NiftySmallcap100StockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY IT" -> NiftyITStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY AUTO" -> NiftyAutoStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY PHARMA" -> NiftyPharmaStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY FMCG" -> NiftyFMCGStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY METAL" -> NiftyMetalStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY REALTY" -> NiftyRealtyStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "NIFTY ENERGY" -> NiftyEnergyStockMaster.allStocks.map {
                    "${it.first} — ${it.second}"
                }
                "OTHER INDICES" -> listOf(
                    "NIFTY 100",
                    "NIFTY NEXT 50",
                    "NIFTY MIDCAP 50",
                    "NIFTY MIDCAP 100",
                    "NIFTY SMALLCAP 100",
                    "NIFTY IT",
                    "NIFTY AUTO",
                    "NIFTY PHARMA",
                    "NIFTY FMCG",
                    "NIFTY METAL",
                    "NIFTY REALTY",
                    "NIFTY ENERGY",
                    "NIFTY 500"
                )
                else -> emptyList()
            }

            "ETFs" -> NseEtfStockMaster.allEtfs.map { etf ->
                "${etf.symbol} — ${etf.name}"
            }

            else -> listOf(
                "GOLD", "GOLD MINI", "GOLD PETAL",
                "SILVER", "SILVER MINI", "SILVER MICRO",
                "CRUDE OIL", "CRUDE OIL MINI",
                "NATURAL GAS", "NATURAL GAS MINI",
                "ZINC", "ZINC MINI", "COPPER",
                "ALUMINIUM", "LEAD MINI", "MENTHA OIL"
            )
        }

        if (symbols.isEmpty()) {
            if (marketTab == "Equity" &&
                marketCategory in setOf("NSE", "BSE") &&
                symbols.isEmpty()
            ) {
                addLabel(
                    body,
                    "$marketCategory master data अभी उपलब्ध नहीं है।",
                    13f,
                    yellow
                )
                return
            }
            return
        }

        symbols.forEach { symbol ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(panel)
                setPadding(dp(14), dp(14), dp(14), dp(14))
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
            row.addView(name)

            row.isClickable = true
            row.isFocusable = true

            row.setOnClickListener {
                val selected = symbol.substringBefore(" — ").trim()

                content.removeAllViews()
                content.addView(
                    HeikinAshiChartScreen(
                        this@MainActivity,
                        initialSymbol = selected,
                        instrumentCategory = marketTab
                    ).createView()
                )
            }

            row.addView(makeText("—", 14f, grey, false))
            body.addView(row)
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
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { dialog.dismiss() }
        }

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            elevation = dp(12).toFloat()
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
                dp(20),
                dp(10),
                dp(20)
            )
            setBackgroundColor(panel)
        }

        val title = makeText(
            "SB BOT",
            22f,
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
            12f,
            yellow,
            true
        ).apply {
            setPadding(
                dp(18),
                dp(18),
                dp(12),
                dp(12)
            )
        }
        drawer.addView(status)

        val menuItems = listOf(
            "Home" to "⌂",
            "Signals" to "↗",
            "Paper" to "▤",
            "Positions" to "▣",
            "Market" to "⌁",
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
                    dp(15),
                    dp(12),
                    dp(15)
                )
                setBackgroundColor(
                    if (currentScreen == item.first) panel else bg
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
                22f,
                yellow,
                true
            )
            icon.gravity = Gravity.CENTER
            icon.layoutParams = LinearLayout.LayoutParams(
                dp(42),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            val label = makeText(
                item.first,
                16f,
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
                24f,
                Color.LTGRAY,
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

    private fun createDashboardDataEntry(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(panel)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }

        addLabel(container, "Dashboard Data Entry", 16f, white, true)

        val capitalEdit = addLabelEditable(container, "Available Capital")
        val pnlEdit = addLabelEditable(container, "Today's P&L")
        val signalsEdit = addLabelEditable(container, "Verified Live Signals")
        val positionsEdit = addLabelEditable(container, "Open Positions")

        capitalEdit.setText(dbHelper.getValue("capital") ?: "")
        pnlEdit.setText(dbHelper.getValue("pnl") ?: "")
        signalsEdit.setText(dbHelper.getValue("signals") ?: "")
        positionsEdit.setText(dbHelper.getValue("positions") ?: "")

        val saveBtn = Button(this).apply {
            text = "Save"
            isAllCaps = false
            setTextColor(white)
            setBackgroundColor(bg)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(8), 0, 0)
            }
            setOnClickListener {
                dbHelper.saveValue("capital", capitalEdit.text.toString())
                dbHelper.saveValue("pnl", pnlEdit.text.toString())
                dbHelper.saveValue("signals", signalsEdit.text.toString())
                dbHelper.saveValue("positions", positionsEdit.text.toString())
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Saved")
                    .setPositiveButton("OK", null)
                    .show()
                showScreen(currentScreen)
            }
        }
        container.addView(saveBtn)
        return container
    }

    private fun addLabelEditable(parent: LinearLayout, title: String): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(6), 0, dp(6))
            }
        }

        addLabel(row, title, 13f, grey)

        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(white)
            setBackgroundColor(bg)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(edit)
        parent.addView(row)
        return edit
    }
}
