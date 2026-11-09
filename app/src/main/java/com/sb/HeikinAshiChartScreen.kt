package com.sb

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.sb.backend.core.BackendManager
import com.sb.backend.market.model.MarketCandle
import com.sb.backend.market.model.MarketTick
import com.sb.backend.market.model.MarketUiState
import com.sb.backend.services.TradeResult
import com.sb.ui.chart.ChartCandle
import com.sb.ui.chart.ChartIndicator
import com.sb.ui.chart.ChartStyle
import com.sb.ui.chart.ChartTool
import com.sb.ui.chart.MarketDepthAdapter
import com.sb.ui.chart.ProfessionalChartView
import com.sb.ui.market.InstrumentLiveViewModel
import com.sb.ui.theme.AppDesign
import com.sb.ui.trade.QuickTradeOverlay
import com.sb.ui.trade.QuickTradeRequest
import com.sb.ui.trade.QuickTradeTracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HeikinAshiChartScreen(
    private val context: Context,
    initialSymbol: String = "NIFTY 50",
    private val instrumentCategory: String = "Market",
    private val exchange: String = "",
    viewModel: InstrumentLiveViewModel? = null,
    private val onFullscreenChanged: ((Boolean) -> Unit)? = null
) {

    private val backgroundColor = AppDesign.background
    private val cardColor = AppDesign.surfaceSolid
    private val accentColor = AppDesign.accent
    private val greenColor = AppDesign.positive
    private val redColor = AppDesign.negative
    private val gridColor = AppDesign.divider
    private val textColor = AppDesign.textPrimary
    private val mutedColor = AppDesign.textMuted

    private val timeframes = arrayOf(
        "1m", "2m", "3m", "5m", "10m", "15m", "30m",
        "1h", "2h", "4h", "1D", "1W", "2W", "4W",
        "1M", "3M", "1Y", "5Y"
    )

    private var selectedSymbol = initialSymbol
    private var selectedTimeframe = "1m"
    private var chartStyle = ChartStyle.CANDLE
    private val activeIndicators = linkedSetOf<ChartIndicator>()
    private var activeTool = ChartTool.CROSSHAIR
    private var magnetEnabled = false

    private val exchangeHint: String =
        exchange.trim().ifBlank {
            when {
                instrumentCategory.contains("MCX", ignoreCase = true) ||
                    instrumentCategory.contains("Commodity", ignoreCase = true) -> "MCX"

                instrumentCategory.contains("BSE", ignoreCase = true) -> "BSE"
                else -> "NSE"
            }.uppercase(Locale.ROOT)
        }.uppercase(Locale.ROOT)

    private val liveViewModel: InstrumentLiveViewModel? =
        viewModel ?: (context as? ViewModelStoreOwner)?.let {
            ViewModelProvider(it).get(InstrumentLiveViewModel::class.java)
        }

    private lateinit var chartView: ProfessionalChartView
    private lateinit var chartColumn: LinearLayout
    private lateinit var chartRow: LinearLayout
    private lateinit var leftToolbar: LinearLayout
    private lateinit var symbolInfo: TextView
    private lateinit var modeButton: Button
    private lateinit var timeframeButton: TextView
    private lateinit var drawingToolsButton: TextView
    private lateinit var fullscreenButton: TextView
    private lateinit var livePriceText: TextView
    private lateinit var liveDetailText: TextView
    private lateinit var disclaimerText: TextView
    private lateinit var depthSummaryText: TextView
    private lateinit var quickTrade: QuickTradeOverlay
    private lateinit var depthAdapter: MarketDepthAdapter

    private lateinit var magnetButton: TextView

    private val toolButtons = LinkedHashMap<ChartTool, TextView>()

    private var isFullscreen = false

    private val bidPriceViews = ArrayList<TextView>()
    private val bidQuantityViews = ArrayList<TextView>()
    private val askPriceViews = ArrayList<TextView>()
    private val askQuantityViews = ArrayList<TextView>()

    private var liveScope: CoroutineScope? = null
    private var currentCandles: List<ChartCandle> = emptyList()
    private var lastTick: MarketTick? = null
    private var pendingViewportReset = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val chartTimeFormat = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    fun createView(): View {
        // The ViewModel is shared activity-wide; keep the visible timeframe in
        // sync with it so re-opening the chart does not briefly reload 1m.
        liveViewModel?.selectedTimeframe?.value?.let {
            selectedTimeframe = it.label
        }

        val container = FrameLayout(context)
        container.setBackgroundColor(backgroundColor)

        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(backgroundColor)
        root.setPadding(dp(10), dp(2), dp(10), dp(4))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = AppDesign.text(
            context,
            "CHART",
            13f,
            accentColor,
            bold = true
        )
        header.addView(title)

        symbolInfo = AppDesign.text(
            context,
            "",
            12f,
            textColor,
            bold = true
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(6), 0)
        }
        header.addView(
            symbolInfo,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        timeframeButton = AppDesign.text(
            context,
            "$selectedTimeframe ▾",
            11f,
            textColor,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.fieldBackground(context, 8)
            isClickable = true
            setOnClickListener { showTimeframeMenu(this) }
        }
        header.addView(
            timeframeButton,
            LinearLayout.LayoutParams(dp(62), dp(32)).apply {
                setMargins(0, 0, dp(6), 0)
            }
        )

        modeButton = Button(context).apply {
            text = chartStyle.label
            isAllCaps = false
            setTextColor(Color.BLACK)
            background = AppDesign.roundedBackground(context, accentColor, 8)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            minWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                chartStyle = when (chartStyle) {
                    ChartStyle.CANDLE -> ChartStyle.HEIKIN_ASHI
                    ChartStyle.HEIKIN_ASHI -> ChartStyle.LINE
                    ChartStyle.LINE -> ChartStyle.BAR
                    ChartStyle.BAR -> ChartStyle.CANDLE
                }
                text = chartStyle.label
                updateChart()
            }
        }
        header.addView(
            modeButton,
            LinearLayout.LayoutParams(dp(52), dp(32)).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        val indicatorButton = AppDesign.text(
            context,
            "ƒx",
            12f,
            accentColor,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.fieldBackground(context, 8)
            isClickable = true
            contentDescription = "Indicators"
            setOnClickListener { showIndicatorMenu(this) }
        }
        header.addView(
            indicatorButton,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        drawingToolsButton = AppDesign.text(
            context,
            "✎",
            13f,
            accentColor,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.fieldBackground(context, 8)
            isClickable = true
            contentDescription = "Drawing tools"
            setOnClickListener {
                leftToolbar.visibility =
                    if (leftToolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                refreshDrawingToolsButton()
            }
        }
        header.addView(
            drawingToolsButton,
            LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                setMargins(0, 0, dp(4), 0)
            }
        )

        fullscreenButton = AppDesign.text(
            context,
            "⤢",
            15f,
            accentColor,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.fieldBackground(context, 8)
            isClickable = true
            contentDescription = "Maximize chart"
            setOnClickListener { setFullscreen(!isFullscreen) }
        }
        header.addView(
            fullscreenButton,
            LinearLayout.LayoutParams(dp(34), dp(32))
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            )
        )
        root.addView(AppDesign.divider(context))

        val liveStrip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        livePriceText = AppDesign.text(
            context,
            "LIVE • waiting for Kotak feed",
            19f,
            mutedColor,
            bold = true
        )
        liveStrip.addView(
            livePriceText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        liveDetailText = AppDesign.text(
            context,
            "LTP, depth, volume and candles update in real time.",
            9.5f,
            mutedColor,
            mono = true
        )
        liveStrip.addView(
            liveDetailText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(liveStrip)

        chartRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        leftToolbar = createLeftToolbar()
        refreshDrawingToolsButton()
        chartRow.addView(
            leftToolbar,
            LinearLayout.LayoutParams(dp(30), LinearLayout.LayoutParams.MATCH_PARENT)
        )

        chartColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        chartView = ProfessionalChartView(context).apply {
            chartStyle = this@HeikinAshiChartScreen.chartStyle
            activeTool = this@HeikinAshiChartScreen.activeTool
            magnetEnabled = this@HeikinAshiChartScreen.magnetEnabled
            indicators = activeIndicators.toSet()
            onTextDrawingRequested = { index, price ->
                showTextDrawingDialog(index, price)
            }
            onCrosshairChanged = { candle, _ ->
                if (candle != null) {
                    renderCrosshairCandle(candle)
                } else {
                    renderLiveDetail(lastTick)
                }
            }
        }
        chartColumn.addView(
            chartView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        chartColumn.addView(
            createDepthPanel(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        chartRow.addView(
            chartColumn,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        root.addView(
            chartRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        disclaimerText = AppDesign.text(
            context,
            "Waiting for Kotak Neo live feed...",
            9f,
            mutedColor
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }

        root.addView(
            disclaimerText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(20)
            )
        )

        container.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        quickTrade = QuickTradeOverlay(
            context = context,
            onOrderRequest = { request -> placeQuickPaperOrder(request) },
            onExitRequest = { trade, exitPrice, reason ->
                closeQuickPaperOrder(trade, exitPrice, reason)
            }
        )
        quickTrade.setInstrument(selectedSymbol, exchangeHint)

        val quickPanelParams = FrameLayout.LayoutParams(
            dp(214),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dp(6)
            rightMargin = dp(6)
        }
        container.addView(quickTrade.view, quickPanelParams)

        startLiveUpdates(container)
        updateChart()
        return container
    }

    fun setFullscreen(fullscreen: Boolean) {
        if (isFullscreen == fullscreen) return
        isFullscreen = fullscreen

        if (::fullscreenButton.isInitialized) {
            fullscreenButton.text = if (fullscreen) "⤡" else "⤢"
            fullscreenButton.contentDescription =
                if (fullscreen) "Minimize chart" else "Maximize chart"
        }

        if (::liveDetailText.isInitialized) {
            liveDetailText.visibility =
                if (fullscreen) View.GONE else View.VISIBLE
        }

        if (::disclaimerText.isInitialized) {
            disclaimerText.visibility =
                if (fullscreen) View.GONE else View.VISIBLE
        }

        if (::chartView.isInitialized) {
            chartView.requestLayout()
        }

        onFullscreenChanged?.invoke(fullscreen)
    }

    private fun showTimeframeMenu(anchor: View) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        var popup: PopupWindow? = null

        timeframes.forEach { label ->
            val selected = label == selectedTimeframe
            val item = AppDesign.text(
                context,
                label,
                12f,
                if (selected) accentColor else textColor,
                bold = selected
            ).apply {
                setPadding(dp(10), dp(7), dp(10), dp(7))
                if (selected) {
                    background = AppDesign.roundedBackground(
                        context,
                        Color.argb(40, 255, 193, 7),
                        7
                    )
                }
                setOnClickListener {
                    val changed = label != selectedTimeframe
                    selectedTimeframe = label
                    (anchor as? TextView)?.text = "$label ▾"
                    popup?.dismiss()
                    if (changed) {
                        pendingViewportReset = true
                    }
                    updateChart()
                }
            }
            list.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(list)
        }

        popup = PopupWindow(
            scroll,
            dp(88),
            dp(230),
            true
        ).apply {
            setBackgroundDrawable(AppDesign.overlayBackground(context, 10))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        popup.showAsDropDown(anchor, -dp(12), dp(2))
    }

    private fun createLeftToolbar(): LinearLayout {
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            setBackgroundColor(Color.TRANSPARENT)
        }

        toolButtons.clear()
        ChartTool.values().forEach { tool ->
            val button = toolButton(tool.shortLabel, tool.label) {
                selectTool(tool)
            }
            toolButtons[tool] = button
            toolbar.addView(button)
        }

        magnetButton = toolButton("M", "Magnet mode") {
            magnetEnabled = !magnetEnabled
            chartView.magnetEnabled = magnetEnabled
            refreshToolButtons()
        }
        toolbar.addView(magnetButton)

        toolbar.addView(toolButton("C", "Clear drawings") {
            chartView.clearDrawings()
        })

        refreshToolButtons()
        refreshDrawingToolsButton()
        return toolbar
    }

    private fun toolButton(
        label: String,
        description: String,
        onClick: () -> Unit
    ): TextView {
        val button = AppDesign.text(
            context,
            label,
            11f,
            textColor,
            bold = true,
            mono = true
        ).apply {
            gravity = Gravity.CENTER
            contentDescription = description
            isClickable = true
            background = AppDesign.roundedBackground(context, cardColor, 6)
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(dp(24), dp(26))
        params.setMargins(0, dp(1), 0, dp(1))
        button.layoutParams = params
        return button
    }

    private fun refreshToolButtons() {
        toolButtons.forEach { (tool, button) ->
            val selected = tool == activeTool
            button.setTextColor(if (selected) Color.BLACK else textColor)
            button.background = AppDesign.roundedBackground(
                context,
                if (selected) accentColor else cardColor,
                6
            )
        }
        if (::magnetButton.isInitialized) {
            magnetButton.setTextColor(if (magnetEnabled) Color.BLACK else textColor)
            magnetButton.background = AppDesign.roundedBackground(
                context,
                if (magnetEnabled) accentColor else cardColor,
                6
            )
        }
    }

    private fun refreshDrawingToolsButton() {
        if (!::drawingToolsButton.isInitialized) return
        val visible = ::leftToolbar.isInitialized && leftToolbar.visibility == View.VISIBLE
        drawingToolsButton.setTextColor(if (visible) Color.BLACK else textColor)
        drawingToolsButton.background = AppDesign.roundedBackground(
            context,
            if (visible) accentColor else cardColor,
            6
        )
    }

    private fun selectTool(tool: ChartTool) {
        activeTool = tool
        if (::chartView.isInitialized) {
            chartView.activeTool = tool
        }
        refreshToolButtons()
    }

    private fun showIndicatorMenu(anchor: View) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val title = AppDesign.text(
            context,
            "INDICATORS",
            10f,
            accentColor,
            bold = true
        )
        title.setPadding(dp(4), dp(2), dp(4), dp(6))
        list.addView(title)

        var popup: PopupWindow? = null

        ChartIndicator.values().forEach { indicator ->
            val item = AppDesign.text(
                context,
                indicatorRowLabel(indicator),
                11f,
                if (indicator in activeIndicators) accentColor else textColor,
                bold = indicator in activeIndicators
            ).apply {
                setPadding(dp(8), dp(7), dp(8), dp(7))
                if (indicator in activeIndicators) {
                    background = AppDesign.roundedBackground(
                        context,
                        Color.argb(40, 255, 193, 7),
                        6
                    )
                }
                setOnClickListener {
                    if (indicator in activeIndicators) {
                        activeIndicators.remove(indicator)
                    } else {
                        activeIndicators.add(indicator)
                    }
                    text = indicatorRowLabel(indicator)
                    setTextColor(if (indicator in activeIndicators) accentColor else textColor)
                    typeface = if (indicator in activeIndicators) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    background = if (indicator in activeIndicators) {
                        AppDesign.roundedBackground(context, Color.argb(40, 255, 193, 7), 6)
                    } else {
                        null
                    }
                    chartView.indicators = activeIndicators.toSet()
                }
            }
            list.addView(
                item,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        list.addView(
            AppDesign.text(context, "Clear all", 10f, mutedColor).apply {
                setPadding(dp(8), dp(8), dp(8), dp(4))
                setOnClickListener {
                    activeIndicators.clear()
                    chartView.indicators = emptySet()
                    popup?.dismiss()
                    showIndicatorMenu(anchor)
                }
            }
        )

        popup = PopupWindow(
            list,
            dp(168),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(AppDesign.overlayBackground(context, 10))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        popup.showAsDropDown(anchor, -dp(40), dp(2))
    }

    private fun indicatorRowLabel(indicator: ChartIndicator): String {
        val check = if (indicator in activeIndicators) "✓  " else "     "
        return check + indicator.label
    }

    private fun showTextDrawingDialog(index: Int, price: Double) {
        val input = EditText(context).apply {
            hint = "Annotation text"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(context)
            .setTitle("Text annotation")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                chartView.addTextDrawing(
                    index,
                    price,
                    input.text?.toString()?.trim().orEmpty().ifBlank { "Note" }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createDepthPanel(): View {
        bidPriceViews.clear()
        bidQuantityViews.clear()
        askPriceViews.clear()
        askQuantityViews.clear()

        val panel = LinearLayout(context)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.TRANSPARENT)
        panel.setPadding(0, dp(2), 0, dp(2))

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, dp(2), 0, 0)
        panel.layoutParams = params

        panel.addView(
            AppDesign.divider(context),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
        )

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(0, dp(3), 0, 0)

        val title = TextView(context).apply {
            text = "MARKET DEPTH (5)"
            textSize = 10f
            setTextColor(accentColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        depthSummaryText = TextView(context).apply {
            text = "TBQ —  TSQ —"
            textSize = 9f
            setTextColor(mutedColor)
            typeface = Typeface.MONOSPACE
        }
        header.addView(depthSummaryText)

        panel.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(18)
            )
        )

        val columns = LinearLayout(context)
        columns.orientation = LinearLayout.HORIZONTAL

        columns.addView(
            createDepthColumn(
                "BID", greenColor,
                bidPriceViews, bidQuantityViews
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, dp(4), 0) }
        )

        columns.addView(
            createDepthColumn(
                "ASK", redColor,
                askPriceViews, askQuantityViews
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), 0, 0, 0) }
        )

        panel.addView(columns)

        depthAdapter = MarketDepthAdapter(
            bidPriceViews = bidPriceViews,
            bidQuantityViews = bidQuantityViews,
            askPriceViews = askPriceViews,
            askQuantityViews = askQuantityViews,
            priceFormatter = { price -> formatPrice(price) },
            quantityFormatter = { quantity -> formatQuantity(quantity) },
            bidColor = greenColor,
            askColor = redColor,
            quantityColor = textColor,
            emptyColor = mutedColor
        )

        return panel
    }

    private fun createDepthColumn(
        label: String,
        labelColor: Int,
        priceViews: MutableList<TextView>,
        quantityViews: MutableList<TextView>
    ): View {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL

        val header = LinearLayout(context)
        header.orientation = LinearLayout.HORIZONTAL

        val labelView = TextView(context).apply {
            text = label
            textSize = 9f
            setTextColor(labelColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(
            labelView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val qtyLabel = TextView(context).apply {
            text = "QTY"
            textSize = 9f
            setTextColor(mutedColor)
            gravity = Gravity.END
        }
        header.addView(qtyLabel)

        column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(14)
            )
        )

        repeat(5) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val price = TextView(context).apply {
                text = "—"
                textSize = 9.5f
                setTextColor(mutedColor)
                typeface = Typeface.MONOSPACE
                maxLines = 1
            }
            row.addView(
                price,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )

            val quantity = TextView(context).apply {
                text = "—"
                textSize = 9.5f
                setTextColor(textColor)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.END
                maxLines = 1
            }
            row.addView(quantity)

            priceViews.add(price)
            quantityViews.add(quantity)
            column.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(14)
                )
            )
        }

        return column
    }

    private fun renderDepth(tick: MarketTick?) {
        depthSummaryText.text = if (tick == null) {
            "TBQ —  TSQ —"
        } else {
            "TBQ ${formatQuantity(tick.totalBuyQty)}  TSQ ${formatQuantity(tick.totalSellQty)}"
        }

        if (::depthAdapter.isInitialized) {
            depthAdapter.bind(tick)
        }
    }

    private fun placeQuickPaperOrder(request: QuickTradeRequest) {
        val scope = liveScope
        if (scope == null) {
            quickTrade.showMessage("Chart active nahi hai.")
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    BackendManager.paperEngine().placeOrder(
                        symbol = request.symbol,
                        side = request.side,
                        quantity = request.quantity,
                        entryPrice = request.entryPrice,
                        exchange = request.exchange,
                        note = "Quick trade ${request.side}"
                    )
                } catch (e: Exception) {
                    TradeResult(
                        success = false,
                        message = e.message ?: "Paper engine unavailable."
                    )
                }
            }

            val order = result.order
            if (!result.success || order == null) {
                quickTrade.onOrderRejected(result.message)
                return@launch
            }

            quickTrade.onOrderPlaced(
                QuickTradeTracking(
                    orderId = order.id,
                    symbol = order.symbol,
                    exchange = order.exchange,
                    side = order.side,
                    quantity = order.quantity,
                    entryPrice = order.entryPrice,
                    stopLoss = request.stopLoss,
                    target = request.target
                )
            )
        }
    }

    private fun closeQuickPaperOrder(
        trade: QuickTradeTracking,
        exitPrice: Double,
        reason: String
    ) {
        val scope = liveScope
        if (scope == null) return

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    BackendManager.paperEngine().closeOrder(
                        orderId = trade.orderId,
                        exitPrice = exitPrice,
                        exchange = trade.exchange
                    )
                } catch (e: Exception) {
                    TradeResult(
                        success = false,
                        message = e.message ?: "Paper engine unavailable."
                    )
                }
            }

            if (result.success) {
                quickTrade.onTradeClosed(reason, result.pnl)
            } else {
                quickTrade.showMessage("$reason • Exit failed: ${result.message}")
            }
        }
    }

    private fun makeButtonRow(parent: LinearLayout): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(0, dp(3), 0, dp(3))

        parent.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        return row
    }

    private fun addChoiceButton(
        row: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val button = Button(context)
        button.text = label
        button.textSize = 10f
        button.setTextColor(textColor)
        button.setBackgroundColor(cardColor)
        button.setPadding(dp(2), 0, dp(2), 0)
        button.setOnClickListener {
            action()
        }

        val params = LinearLayout.LayoutParams(
            0,
            dp(42),
            1f
        )
        params.setMargins(dp(2), 0, dp(2), 0)
        row.addView(button, params)
    }

    private fun updateChart() {
        if (!::chartView.isInitialized) {
            return
        }

        if (::symbolInfo.isInitialized) {
            symbolInfo.text = "$selectedSymbol • $exchangeHint"
        }

        liveViewModel?.setTimeframe(selectedTimeframe)

        // A timeframe switch re-renders with a fresh viewport from the
        // incoming candle list. Style/indicator changes keep the viewport.
        if (!pendingViewportReset) {
            chartView.setChartData(
                currentCandles,
                chartStyle,
                preserveViewport = true
            )
        }
    }

    private fun startLiveUpdates(root: View) {
        liveScope?.cancel()
        currentCandles = emptyList()

        val vm = liveViewModel

        if (vm == null) {
            livePriceText.text = "LIVE • unavailable"
            livePriceText.setTextColor(mutedColor)
            liveDetailText.text = "Live market ViewModel is not available."
            disclaimerText.text = "LIVE DATA UNAVAILABLE"
            return
        }

        vm.setInstrument(exchangeHint, selectedSymbol)

        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate
        )
        liveScope = scope

        scope.launch {
            vm.uiState.collect { state: MarketUiState ->
                renderState(state)
            }
        }

        scope.launch {
            vm.candles.collect { candles: List<MarketCandle> ->
                renderCandles(candles)
            }
        }

        root.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                }

                override fun onViewDetachedFromWindow(v: View) {
                    stopLiveUpdates()
                }
            }
        )
    }

    private fun stopLiveUpdates() {
        liveScope?.cancel()
        liveScope = null
        liveViewModel?.releaseInstrument()
    }

    private fun renderState(state: MarketUiState) {
        if (!::livePriceText.isInitialized) return

        val tick = state.tick
        val activeKey = state.key

        if (tick != null && activeKey != null &&
            tick.key.key() != activeKey.key()
        ) {
            return
        }

        if (tick == null) {
            lastTick = null
            renderDepth(null)
            livePriceText.text = "LIVE • waiting for tick"
            livePriceText.setTextColor(mutedColor)
            liveDetailText.text = buildString {
                append(state.connectionState.name)
                append("  •  ")
                append(if (state.isSubscribed) "SUBSCRIBED" else "WAITING")
                append("  •  ").append(selectedSymbol)
                val message = state.chartStatus.message
                if (message.isNotBlank()) {
                    append('\n').append(message)
                }
            }
            disclaimerText.text = buildString {
                append(state.chartStatus.state)
                append(" • ").append(state.connectionState.name)
                append(" • Candles ").append(state.chartStatus.candleCount)
                val message = state.chartStatus.message
                if (message.isNotBlank()) {
                    append(" • ").append(message)
                }
            }
            return
        }

        lastTick = tick

        val previousClose = tick.previousClose ?: tick.close
        val change = tick.change
            ?: previousClose?.let { tick.ltp - it }
            ?: 0.0
        val changePercent = tick.changePercent
            ?: previousClose?.takeIf { it > 0.0 }
                ?.let { change / it * 100.0 }
            ?: 0.0

        livePriceText.text = String.format(
            Locale.US,
            "LTP ₹%s   %s %s (%.2f%%)",
            formatPrice(tick.ltp),
            if (change >= 0.0) "▲" else "▼",
            formatPrice(abs(change)),
            changePercent
        )
        livePriceText.setTextColor(
            if (change >= 0.0) greenColor else redColor
        )

        renderLiveDetail(tick)

        symbolInfo.text = "${tick.key.symbol} • ${tick.key.exchange}"
        disclaimerText.text = buildString {
            append(if (state.isSubscribed) "LIVE • SUBSCRIBED" else "LIVE • NOT SUBSCRIBED")
            append(" • ").append(state.connectionState.name)
            append(" • Candles ").append(state.chartStatus.candleCount)
            val message = state.chartStatus.message
            if (message.isNotBlank() && !state.chartStatus.liveActive) {
                append(" • ").append(message)
            }
        }

        renderDepth(tick)
        if (::quickTrade.isInitialized) {
            quickTrade.onTick(tick)
        }
    }

    private fun renderLiveDetail(tick: MarketTick?) {
        if (!::liveDetailText.isInitialized) return
        if (tick == null) {
            liveDetailText.text = "O —  H —  L —  C —\nV —  •  $selectedSymbol"
            return
        }
        liveDetailText.text = buildString {
            append("O ").append(formatPrice(tick.open))
            append("  H ").append(formatPrice(tick.high))
            append("  L ").append(formatPrice(tick.low))
            append("  PC ").append(formatPrice(tick.previousClose))
            append('\n')
            append("V ").append(formatQuantity(tick.volume))
            append("  OI ").append(formatQuantity(tick.oi))
            append("  •  ").append(timeFormat.format(Date(tick.receivedAt)))
        }
    }

    private fun renderCrosshairCandle(candle: ChartCandle) {
        if (!::liveDetailText.isInitialized) return
        liveDetailText.text = buildString {
            append("O ").append(chartPrice(candle.open))
            append("  H ").append(chartPrice(candle.high))
            append("  L ").append(chartPrice(candle.low))
            append("  C ").append(chartPrice(candle.close))
            append('\n')
            append("V ").append(formatQuantity(candle.volume))
            if (candle.timestamp > 0L) {
                append("  •  ").append(chartTimeFormat.format(Date(candle.timestamp)))
            }
        }
    }

    private fun chartPrice(value: Double): String {
        if (!value.isFinite()) return "—"
        return String.format(Locale.US, "%.2f", value)
    }

    private fun renderCandles(candles: List<MarketCandle>) {
        val firstTs = candles.firstOrNull()?.timestamp ?: 0L
        val lastTs = candles.lastOrNull()?.timestamp ?: 0L
        Log.i("ChartCandleAudit", "symbol=" + selectedSymbol + " exchange=" + exchangeHint + " timeframe=" + selectedTimeframe + " count=" + candles.size + " first=" + firstTs + " last=" + lastTs)
        currentCandles = candles.map {
            ChartCandle(
                timestamp = it.timestamp,
                open = it.open,
                high = it.high,
                low = it.low,
                close = it.close,
                volume = it.volume
            )
        }

        if (::chartView.isInitialized) {
            val resetViewport = pendingViewportReset
            pendingViewportReset = false
            chartView.setChartData(
                currentCandles,
                chartStyle,
                preserveViewport = !resetViewport
            )
        }
    }

    private fun formatPrice(value: Double?): String {
        if (value == null || !value.isFinite()) return "—"
        return String.format(Locale.US, "%.2f", value)
    }

    private fun formatQuantity(value: Long): String {
        val magnitude = abs(value)
        return when {
            magnitude >= 10_000_000L ->
                String.format(Locale.US, "%.2fCr", value / 10_000_000.0)

            magnitude >= 100_000L ->
                String.format(Locale.US, "%.2fL", value / 100_000.0)

            magnitude >= 1_000L ->
                String.format(Locale.US, "%.1fK", value / 1_000.0)

            else -> value.toString()
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
