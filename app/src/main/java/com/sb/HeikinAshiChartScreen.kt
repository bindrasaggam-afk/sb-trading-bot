package com.sb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class HeikinAshiChartScreen(
    private val context: Context,
    initialSymbol: String = "NIFTY 50",
    private val instrumentCategory: String = "Market"
) {

    private val backgroundColor = Color.rgb(18, 18, 18)
    private val cardColor = Color.rgb(30, 30, 30)
    private val accentColor = Color.rgb(255, 193, 7)
    private val greenColor = Color.rgb(76, 175, 80)
    private val redColor = Color.rgb(244, 67, 54)
    private val gridColor = Color.rgb(55, 55, 55)
    private val textColor = Color.rgb(230, 230, 230)
    private val mutedColor = Color.rgb(155, 155, 155)

    private var selectedSymbol = initialSymbol
    private var selectedTimeframe = "1m"
    private var heikinAshiMode = true

    private lateinit var chartView: CandleChartView
    private lateinit var symbolInfo: TextView
    private lateinit var modeButton: Button

    fun createView(): View {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(backgroundColor)
        root.setPadding(dp(10), dp(8), dp(10), dp(8))

        val title = TextView(context)
        title.text = "SB BOT — CHART"
        title.setTextColor(accentColor)
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.gravity = Gravity.CENTER_VERTICAL
        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        )

        symbolInfo = TextView(context)
        symbolInfo.text = "$selectedSymbol  •  $selectedTimeframe"
        symbolInfo.setTextColor(textColor)
        symbolInfo.textSize = 15f
        root.addView(
            symbolInfo,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(30)
            )
        )

        val categoryInfo = TextView(context).apply {
            text = "Category: $instrumentCategory"
            setTextColor(mutedColor)
            textSize = 12f
        }
        root.addView(categoryInfo)

        val timeframeSpinner = Spinner(context)

        val timeframes = arrayOf(
            "1m", "2m", "3m", "5m", "10m", "15m", "30m",
            "1h", "4h", "1D", "1W", "2W", "4W",
            "1M", "3M", "1Y", "5Y"
        )

        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            timeframes
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        timeframeSpinner.adapter = adapter
        timeframeSpinner.setSelection(0)

        timeframeSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedTimeframe = timeframes[position]
                    updateChart()
                }

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {
                }
            }

        root.addView(
            timeframeSpinner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        val modeRow = makeButtonRow(root)

        modeButton = Button(context)
        modeButton.text = "Heikin Ashi"
        modeButton.setTextColor(Color.BLACK)
        modeButton.setBackgroundColor(accentColor)
        modeButton.textSize = 12f
        modeButton.setOnClickListener {
            heikinAshiMode = !heikinAshiMode
            modeButton.text =
                if (heikinAshiMode) "Heikin Ashi" else "Normal Candles"
            updateChart()
        }

        modeRow.addView(
            modeButton,
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        // चार्ट व्यू
        chartView = CandleChartView(context)
        root.addView(
            chartView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // --- आर्डर प्लेसमेंट पैनल (BUY / SELL बटन) ---
        val orderPanel = LinearLayout(context)
        orderPanel.orientation = LinearLayout.HORIZONTAL
        orderPanel.setPadding(0, dp(4), 0, dp(4))

        val buyButton = Button(context)
        buyButton.text = "BUY / CALL 🟢"
        buyButton.setTextColor(Color.WHITE)
        buyButton.setBackgroundColor(greenColor)
        buyButton.textSize = 13f
        buyButton.typeface = Typeface.DEFAULT_BOLD
        buyButton.setOnClickListener {
            Toast.makeText(
                context,
                "DEMO ONLY: BUY order placed nahi hua.",
                Toast.LENGTH_LONG
            ).show()
        }

        val sellButton = Button(context)
        sellButton.text = "SELL / PUT 🔴"
        sellButton.setTextColor(Color.WHITE)
        sellButton.setBackgroundColor(redColor)
        sellButton.textSize = 13f
        sellButton.typeface = Typeface.DEFAULT_BOLD
        sellButton.setOnClickListener {
            Toast.makeText(
                context,
                "DEMO ONLY: SELL order placed nahi hua.",
                Toast.LENGTH_LONG
            ).show()
        }

        val buyParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        buyParams.setMargins(dp(2), 0, dp(2), 0)
        orderPanel.addView(buyButton, buyParams)

        val sellParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        sellParams.setMargins(dp(2), 0, dp(2), 0)
        orderPanel.addView(sellButton, sellParams)

        root.addView(
            orderPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        )
        // ---------------------------------------------

        val disclaimer = TextView(context)
        disclaimer.text =
            "DEMO DATA ONLY • Live prices are not connected"
        disclaimer.setTextColor(accentColor)
        disclaimer.textSize = 11f
        disclaimer.gravity = Gravity.CENTER
        disclaimer.setPadding(0, dp(4), 0, dp(4))

        root.addView(
            disclaimer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28)
            )
        )

        updateChart()
        return root
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
            symbolInfo.text = "$selectedSymbol  •  $selectedTimeframe"
        }

        chartView.setChartData(
            createDemoCandles(selectedSymbol, selectedTimeframe),
            heikinAshiMode
        )
    }

    private fun createDemoCandles(
        symbol: String,
        timeframe: String
    ): List<Candle> {
        val result = ArrayList<Candle>()
        val random = Random(
            (symbol + timeframe).hashCode().toLong() + 2026L
        )

        var price = when (symbol) {
            "NIFTY 50" -> 24500.0
            "BANK NIFTY" -> 52000.0
            else -> 1450.0
        }

        var previousClose = price

        for (i in 0 until 80) {
            val open = previousClose
            val movement = (random.nextDouble() - 0.48) *
                when (symbol) {
                    "BANK NIFTY" -> 120.0
                    "NIFTY 50" -> 45.0
                    else -> 12.0
                }

            price = max(1.0, open + movement)

            val high = max(open, price) +
                random.nextDouble() * abs(movement + 1.0)

            val low = min(open, price) -
                random.nextDouble() * abs(movement + 1.0)

            val close = price
            val volume = (random.nextDouble() * 400000 + 50000).toLong()

            result.add(
                Candle(
                    open = open,
                    high = max(high, low),
                    low = min(low, high),
                    close = close,
                    volume = volume
                )
            )

            previousClose = close
        }

        return result
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    data class Candle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Long = 100000L
    )

    private class CandleChartView(context: Context) : View(context) {

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val candlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tooltipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        private val supportPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val resistancePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val srTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val volumePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val entryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val slPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tradeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var candles: List<Candle> = emptyList()
        private var useHeikinAshi = true
        private var scrollOffset = 0f
        private var lastTouchX = 0f
        
        private var currentVisibleCount = 40f
        private val scaleGestureDetector: ScaleGestureDetector

        private var isTouching = false
        private var touchX = 0f
        private var touchY = 0f

        private val density = context.resources.displayMetrics.density

        private fun dp(value: Float): Float {
            return value * density
        }

        init {
            setBackgroundColor(Color.rgb(18, 18, 18))

            gridPaint.color = Color.rgb(55, 55, 55)
            gridPaint.strokeWidth = dp(1f)
            gridPaint.style = Paint.Style.STROKE

            candlePaint.style = Paint.Style.FILL
            candlePaint.strokeWidth = dp(1.4f)

            textPaint.color = Color.rgb(170, 170, 170)
            textPaint.textSize = dp(10f)

            labelPaint.color = Color.WHITE
            labelPaint.textSize = dp(11f)
            labelPaint.typeface = Typeface.DEFAULT_BOLD

            crosshairPaint.color = Color.rgb(200, 200, 200)
            crosshairPaint.strokeWidth = dp(0.8f)
            crosshairPaint.style = Paint.Style.STROKE

            tooltipBgPaint.color = Color.argb(220, 40, 40, 40)
            tooltipBgPaint.style = Paint.Style.FILL

            tooltipPaint.color = Color.WHITE
            tooltipPaint.textSize = dp(10.5f)

            supportPaint.color = Color.rgb(0, 200, 150)
            supportPaint.strokeWidth = dp(1.2f)
            supportPaint.style = Paint.Style.STROKE

            resistancePaint.color = Color.rgb(255, 100, 100)
            resistancePaint.strokeWidth = dp(1.2f)
            resistancePaint.style = Paint.Style.STROKE

            srTextPaint.textSize = dp(9.5f)
            srTextPaint.typeface = Typeface.DEFAULT_BOLD

            volumePaint.style = Paint.Style.FILL

            entryPaint.color = Color.rgb(0, 150, 255)
            entryPaint.strokeWidth = dp(1.5f)
            entryPaint.style = Paint.Style.STROKE

            slPaint.color = Color.rgb(255, 69, 58)
            slPaint.strokeWidth = dp(1.5f)
            slPaint.style = Paint.Style.STROKE

            targetPaint.color = Color.rgb(52, 199, 89)
            targetPaint.strokeWidth = dp(1.5f)
            targetPaint.style = Paint.Style.STROKE

            tradeTextPaint.textSize = dp(9.5f)
            tradeTextPaint.typeface = Typeface.DEFAULT_BOLD

            scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    currentVisibleCount /= detector.scaleFactor
                    val maxLimit = if (candles.isNotEmpty()) candles.size.toFloat() else 80f
                    currentVisibleCount = max(8f, min(maxLimit, currentVisibleCount))
                    invalidate()
                    return true
                }
            })
        }

        fun setChartData(
            source: List<Candle>,
            heikinAshi: Boolean
        ) {
            candles = if (heikinAshi) {
                calculateHeikinAshi(source)
            } else {
                ArrayList(source)
            }
            useHeikinAshi = heikinAshi
            scrollOffset = 0f
            currentVisibleCount = 40f
            isTouching = false
            invalidate()
        }

        private fun calculateHeikinAshi(
            source: List<Candle>
        ): List<Candle> {
            val result = ArrayList<Candle>()
            if (source.isEmpty()) return result

            var previousHaOpen = (source[0].open + source[0].close) / 2.0
            var previousHaClose = (
                source[0].open +
                    source[0].high +
                    source[0].low +
                    source[0].close
                ) / 4.0

            for (i in source.indices) {
                val candle = source[i]
                val haClose = (
                    candle.open +
                        candle.high +
                        candle.low +
                        candle.close
                    ) / 4.0

                val haOpen = if (i == 0) {
                    (candle.open + candle.close) / 2.0
                } else {
                    (previousHaOpen + previousHaClose) / 2.0
                }

                val haHigh = max(
                    candle.high,
                    max(haOpen, haClose)
                )

                val haLow = min(
                    candle.low,
                    min(haOpen, haClose)
                )

                result.add(
                    Candle(
                        open = haOpen,
                        high = haHigh,
                        low = haLow,
                        close = haClose,
                        volume = candle.volume
                    )
                )

                previousHaOpen = haOpen
                previousHaClose = haClose
            }

            return result
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val data = candles
            if (data.isEmpty()) {
                textPaint.color = Color.WHITE
                canvas.drawText(
                    "No candle data",
                    dp(20f),
                    dp(30f),
                    textPaint
                )
                return
            }

            val left = dp(8f)
            val top = dp(12f)
            val right = width - dp(65f)
            val bottom = height - dp(22f)

            if (right <= left || bottom <= top) return

            val chartWidth = right - left
            val chartHeight = bottom - top

            val priceChartBottom = top + chartHeight * 0.72f
            val volumeChartTop = priceChartBottom + dp(12f)
            val volumeChartHeight = chartHeight * 0.22f

            val visibleCount = max(
                1,
                min(data.size, currentVisibleCount.toInt())
            )

            val maxStart = max(0, data.size - visibleCount)
            val start = min(
                maxStart,
                max(0, scrollOffset.toInt())
            )
            val end = min(data.size, start + visibleCount)

            if (start >= end) return

            var maxPrice = data[start].high
            var minPrice = data[start].low
            var maxVolume = 1L

            for (i in start until end) {
                if (data[i].high > maxPrice) {
                    maxPrice = data[i].high
                }
                if (data[i].low < minPrice) {
                    minPrice = data[i].low
                }
                if (data[i].volume > maxVolume) {
                    maxVolume = data[i].volume
                }
            }

            var priceRange = maxPrice - minPrice
            if (priceRange <= 0.0) {
                priceRange = 1.0
            }

            val padding = priceRange * 0.08
            maxPrice += padding
            minPrice -= padding
            priceRange = maxPrice - minPrice

            fun priceY(price: Double): Float {
                return priceChartBottom -
                    (((price - minPrice) / priceRange) * (priceChartBottom - top)).toFloat()
            }

            // Grid lines and price labels
            for (i in 0..4) {
                val y = top + (priceChartBottom - top) * i / 4f

                canvas.drawLine(
                    left,
                    y,
                    right,
                    y,
                    gridPaint
                )

                val price = maxPrice -
                    (priceRange * i / 4.0)

                canvas.drawText(
                    String.format("%.2f", price),
                    right + dp(5f),
                    y + dp(4f),
                    textPaint
                )
            }

            // Support and Resistance lines
            var resistanceLevel = data[start].high
            var supportLevel = data[start].low
            for (i in start until end) {
                if (data[i].high > resistanceLevel) {
                    resistanceLevel = data[i].high
                }
                if (data[i].low < supportLevel) {
                    supportLevel = data[i].low
                }
            }

            val resY = priceY(resistanceLevel)
            val supY = priceY(supportLevel)

            if (resY in top..priceChartBottom) {
                canvas.drawLine(left, resY, right, resY, resistancePaint)
                srTextPaint.color = Color.rgb(255, 100, 100)
                canvas.drawText(
                    "Res: %.2f".format(resistanceLevel),
                    left + dp(4f),
                    resY - dp(4f),
                    srTextPaint
                )
            }

            if (supY in top..priceChartBottom) {
                canvas.drawLine(left, supY, right, supY, supportPaint)
                srTextPaint.color = Color.rgb(0, 200, 150)
                canvas.drawText(
                    "Sup: %.2f".format(supportLevel),
                    left + dp(4f),
                    supY + dp(12f),
                    srTextPaint
                )
            }

            // Trade Setup Levels (Buy, SL, Target)
            val lastCandle = data.last()
            val buyPrice = lastCandle.close
            val stopLossPrice = supportLevel - (priceRange * 0.03)
            val targetPrice = resistanceLevel + (priceRange * 0.03)

            val buyY = priceY(buyPrice)
            val slY = priceY(stopLossPrice)
            val targetY = priceY(targetPrice)

            if (targetY in top..priceChartBottom) {
                canvas.drawLine(left, targetY, right, targetY, targetPaint)
                tradeTextPaint.color = Color.rgb(52, 199, 89)
                canvas.drawText(
                    "🎯 Target: %.2f".format(targetPrice),
                    right - dp(115f),
                    targetY - dp(4f),
                    tradeTextPaint
                )
            }

            if (buyY in top..priceChartBottom) {
                canvas.drawLine(left, buyY, right, buyY, entryPaint)
                tradeTextPaint.color = Color.rgb(0, 150, 255)
                canvas.drawText(
                    "🔵 Buy: %.2f".format(buyPrice),
                    right - dp(100f),
                    buyY - dp(4f),
                    tradeTextPaint
                )
            }

            if (slY in top..priceChartBottom) {
                canvas.drawLine(left, slY, right, slY, slPaint)
                tradeTextPaint.color = Color.rgb(255, 69, 58)
                canvas.drawText(
                    "🛑 SL: %.2f".format(stopLossPrice),
                    right - dp(90f),
                    slY + dp(12f),
                    tradeTextPaint
                )
            }

            val slotWidth = chartWidth / visibleCount.toFloat()
            val candleWidth = max(
                dp(1.5f),
                min(dp(12f), slotWidth * 0.65f)
            )

            var selectedCandle: Candle? = null
            var selectedCandleIndex = -1

            for (i in start until end) {
                val candle = data[i]
                val x = left +
                    (i - start) * slotWidth +
                    slotWidth / 2f

                val openY = priceY(candle.open)
                val closeY = priceY(candle.close)
                val highY = priceY(candle.high)
                val lowY = priceY(candle.low)

                val isBullish = candle.close >= candle.open
                val candleColor = if (isBullish) {
                    Color.rgb(76, 175, 80)
                } else {
                    Color.rgb(244, 67, 54)
                }

                // Volume Histogram
                val volHeight = (candle.volume.toFloat() / maxVolume) * volumeChartHeight
                val volTop = bottom - volHeight
                volumePaint.color = if (isBullish) {
                    Color.argb(140, 76, 175, 80)
                } else {
                    Color.argb(140, 244, 67, 54)
                }
                canvas.drawRect(
                    x - candleWidth / 2f,
                    volTop,
                    x + candleWidth / 2f,
                    bottom.toFloat(),
                    volumePaint
                )

                // Candle wick
                candlePaint.color = candleColor
                canvas.drawLine(
                    x,
                    highY,
                    x,
                    lowY,
                    candlePaint
                )

                // Candle body
                val bodyTop = min(openY, closeY)
                val bodyBottom = max(openY, closeY)
                val minimumBodyHeight = dp(1.5f)

                canvas.drawRect(
                    x - candleWidth / 2f,
                    bodyTop,
                    x + candleWidth / 2f,
                    max(bodyTop + minimumBodyHeight, bodyBottom),
                    candlePaint
                )

                if (isTouching && abs(touchX - x) <= slotWidth / 2f) {
                    selectedCandle = candle
                    selectedCandleIndex = i
                }
            }

            // Crosshair and Tooltip
            if (isTouching && selectedCandle != null) {
                val x = left +
                    (selectedCandleIndex - start) * slotWidth +
                    slotWidth / 2f

                canvas.drawLine(x, top, x, bottom, crosshairPaint)
                canvas.drawLine(left, touchY, right, touchY, crosshairPaint)

                val infoText = "O: %.2f  H: %.2f  L: %.2f  C: %.2f  V: %,d".format(
                    selectedCandle.open,
                    selectedCandle.high,
                    selectedCandle.low,
                    selectedCandle.close,
                    selectedCandle.volume
                )

                val boxWidth = dp(260f)
                val boxHeight = dp(20f)
                val boxLeft = left + dp(5f)
                val boxTop = top + dp(5f)

                canvas.drawRect(
                    boxLeft,
                    boxTop,
                    boxLeft + boxWidth,
                    boxTop + boxHeight,
                    tooltipBgPaint
                )
                canvas.drawText(
                    infoText,
                    boxLeft + dp(6f),
                    boxTop + dp(14f),
                    tooltipPaint
                )
            }

            labelPaint.color = Color.rgb(255, 193, 7)
            canvas.drawText(
                if (useHeikinAshi) "HEIKIN ASHI + TRADING" else "CANDLE + TRADING",
                left,
                top + dp(12f),
                labelPaint
            )

            textPaint.color = Color.rgb(170, 170, 170)
            canvas.drawText(
                if (isTouching) "Pinch to zoom • Drag to explore" else "Pinch to zoom • Tap/Drag to view price",
                left,
                height - dp(5f),
                textPaint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleGestureDetector.onTouchEvent(event)

            if (scaleGestureDetector.isInProgress) {
                isTouching = false
                return true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isTouching = true
                    lastTouchX = event.x
                    touchX = event.x
                    touchY = event.y
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    touchX = event.x
                    touchY = event.y

                    val delta = event.x - lastTouchX
                    lastTouchX = event.x

                    val step = max(1f, dp(8f))
                    scrollOffset -= delta / step

                    val maxOffset = max(0, candles.size - 1).toFloat()
                    if (scrollOffset < 0f) scrollOffset = 0f
                    if (scrollOffset > maxOffset) scrollOffset = maxOffset

                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    invalidate()
                    performClick()
                    return true
                }
            }

            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
