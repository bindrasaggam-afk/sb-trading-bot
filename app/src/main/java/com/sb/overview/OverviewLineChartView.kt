package com.sb.overview

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import com.sb.ui.theme.AppDesign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class OverviewLineChartView(context: Context) : View(context) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.divider
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.2f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.textMuted
        textSize = 18f
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppDesign.textMuted
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    private val dateFormat = SimpleDateFormat("dd MMM yy", Locale.US)

    private var points: List<OverviewHistoryPoint> = emptyList()
    private var rangeLabel: String = ""

    fun setData(
        data: List<OverviewHistoryPoint>,
        rangeLabel: String
    ) {
        this.points = data.filter { it.close.isFinite() }
        this.rangeLabel = rangeLabel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val density = resources.displayMetrics.density
        val left = 6f * density
        val right = width.toFloat() - 58f * density
        val top = 8f * density
        val bottom = height.toFloat() - 20f * density

        if (points.isEmpty() || right <= left || bottom <= top) {
            canvas.drawText(
                "No historical daily candles",
                width / 2f,
                height / 2f,
                emptyPaint
            )
            return
        }

        val closes = points.map { it.close }
        val finiteLows = points.mapNotNull { it.low.takeIf { value -> value.isFinite() } }
        val finiteHighs = points.mapNotNull { it.high.takeIf { value -> value.isFinite() } }
        val lowestClose = closes.minOf { it }
        val highestClose = closes.maxOf { it }
        var minPrice = minOf(lowestClose, finiteLows.minOrNull() ?: lowestClose)
        var maxPrice = maxOf(highestClose, finiteHighs.maxOrNull() ?: highestClose)
        if (!minPrice.isFinite() || !maxPrice.isFinite() || maxPrice < minPrice) {
            minPrice = lowestClose
            maxPrice = highestClose
        }
        if (maxPrice <= minPrice) {
            maxPrice = minPrice + 1.0
        }

        val padding = (maxPrice - minPrice) * 0.08
        minPrice -= padding
        maxPrice += padding

        fun priceY(price: Double): Float {
            val fraction = ((price - minPrice) / (maxPrice - minPrice))
                .toFloat()
                .coerceIn(0f, 1f)
            return bottom - fraction * (bottom - top)
        }

        fun indexX(index: Int): Float {
            if (points.size == 1) return (left + right) / 2f
            return left + index.toFloat() /
                (points.size - 1).toFloat() * (right - left)
        }

        for (step in 0..3) {
            val y = top + (bottom - top) * step / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
            val price = maxPrice - (maxPrice - minPrice) * step / 3.0
            canvas.drawText(
                formatPrice(price),
                right + 4f * density,
                y + 5f * density,
                labelPaint
            )
        }

        val linePath = Path()
        points.forEachIndexed { index, point ->
            val x = indexX(index)
            val y = priceY(point.close)
            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }

        val rising = points.last().close >= points.first().close
        val lineColor = if (rising) AppDesign.positive else AppDesign.negative
        linePaint.color = lineColor

        val fillPath = Path(linePath).apply {
            lineTo(indexX(points.lastIndex), bottom)
            lineTo(indexX(0), bottom)
            close()
        }
        fillPaint.shader = LinearGradient(
            0f,
            top,
            0f,
            bottom,
            intArrayOf(
                colorWithAlpha(lineColor, 70),
                colorWithAlpha(lineColor, 0)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        val last = points.last()
        canvas.drawCircle(
            indexX(points.lastIndex),
            priceY(last.close),
            3.4f * density,
            linePaint
        )

        val startLabel = dateFormat.format(Date(points.first().timestamp))
        val endLabel = dateFormat.format(Date(points.last().timestamp))
        canvas.drawText(startLabel, left, height - 4f * density, labelPaint)

        val endWidth = labelPaint.measureText(endLabel)
        canvas.drawText(
            endLabel,
            right - endWidth,
            height - 4f * density,
            labelPaint
        )

        if (rangeLabel.isNotBlank()) {
            val rangeWidth = labelPaint.measureText(rangeLabel)
            canvas.drawText(
                rangeLabel,
                max(left, (left + right) / 2f - rangeWidth / 2f),
                top + 14f * density,
                labelPaint
            )
        }
    }

    private fun colorWithAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun formatPrice(value: Double): String {
        if (!value.isFinite()) return "—"
        return String.format(Locale.US, "%.2f", value)
    }
}
