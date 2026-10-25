package com.sb

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private val bg = Color.rgb(18, 18, 18)
    private val panel = Color.rgb(30, 30, 30)
    private val yellow = Color.rgb(255, 193, 7)
    private val white = Color.WHITE
    private val grey = Color.rgb(160, 160, 160)

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private var currentScreen = "Home"
    private var marketTab = "Equity"
    private lateinit var dbHelper: DashboardDbHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        dbHelper = DashboardDbHelper(this)
        buildApp()
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
        content.removeAllViews()

        // यहाँ सुधार किया गया है ताकि ScrollView पूरी स्क्रीन (MATCH_PARENT) पर फैले
        val scroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true // यह स्लाइडर/कंटेंट को पूरी स्क्रीन पर फैला देगा
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
                content.addView(PaperTradingScreen.create(this))
            }
            "Positions" -> showPositions(body)
            "Market" -> showMarket(body)
            "Settings" -> showSettings(body)
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

        addLabel(body, "Market Watch", 18f, yellow, true)
        addLabel(body, "Live prices are not connected yet.", 13f, grey)

        listOf(
            "NIFTY 50",
            "BANK NIFTY",
            "RELIANCE",
            "GOLD",
            "SILVER"
        ).forEach { symbol ->
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

            row.addView(name)
            row.addView(makeText("—", 14f, grey, false))
            body.addView(row)
        }
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        listOf("Equity", "Index", "MCX").forEach { tab ->
            val button = Button(this).apply {
                text = tab
                isAllCaps = false
                setTextColor(if (marketTab == tab) yellow else white)
                setBackgroundColor(panel)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
                    .apply { setMargins(dp(2), 0, dp(2), 0) }
                setOnClickListener {
                    marketTab = tab
                    showScreen("Market")
                }
            }
            tabs.addView(button)
        }
        body.addView(tabs)

        addLabel(body, "$marketTab Watchlist", 17f, yellow, true)
        addLabel(body, "Price/signal: — (Live data not connected)", 12f, grey)

        val symbols = when (marketTab) {
            "Equity" -> listOf(
                "RELIANCE", "HDFCBANK", "ICICIBANK", "INFY",
                "TCS", "SBIN", "ITC", "LT", "BHARTIARTL"
            )
            "Index" -> listOf(
                "NIFTY 50", "BANK NIFTY", "FINNIFTY",
                "NIFTY MIDCAP SELECT"
            )
            else -> listOf(
                "GOLD", "GOLD MINI", "GOLD PETAL",
                "SILVER", "SILVER MINI", "SILVER MICRO",
                "CRUDE OIL", "CRUDE OIL MINI",
                "NATURAL GAS", "NATURAL GAS MINI",
                "ZINC", "ZINC MINI", "COPPER",
                "ALUMINIUM", "LEAD MINI", "MENTHA OIL"
            )
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
                ).apply { setMargins(0, 0, 0, dp(6)) }
            }

            val name = makeText(symbol, 14f, white, true)
            name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(name)
            row.addView(makeText("—", 14f, grey, false))
            body.addView(row)
        }
    }

    private fun showSettings(body: LinearLayout) {
        addLabel(body, "Settings", 22f, white, true)
        addCard(body, "Backend Connection", "Not verified")
        addLabel(body, "इस UI update में backend settings नहीं बदली गई हैं।", 13f, grey)
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
