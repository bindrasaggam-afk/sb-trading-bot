package com.sb.ui.theme

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object AppDesign {

    const val HEADER_HEIGHT_DP = 48
    const val FOOTER_HEIGHT_DP = 54
    const val BODY_HORIZONTAL_DP = 14
    const val CARD_RADIUS_DP = 12
    const val OVERLAY_RADIUS_DP = 16

    val background: Int = Color.rgb(11, 12, 15)
    val surface: Int = Color.argb(190, 21, 23, 28)
    val surfaceSolid: Int = Color.rgb(21, 23, 28)
    val overlay: Int = Color.argb(200, 18, 20, 25)
    val field: Int = Color.argb(180, 32, 35, 42)
    val scrim: Int = Color.argb(70, 0, 0, 0)
    val textPrimary: Int = Color.rgb(241, 244, 248)
    val textMuted: Int = Color.rgb(148, 158, 171)
    val accent: Int = Color.rgb(255, 193, 7)
    val positive: Int = Color.rgb(34, 197, 94)
    val negative: Int = Color.rgb(239, 68, 68)
    val divider: Int = Color.argb(36, 255, 255, 255)

    fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int = textPrimary,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView {
        return TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            when {
                mono -> typeface = Typeface.MONOSPACE
                bold -> typeface = Typeface.DEFAULT_BOLD
            }
            includeFontPadding = false
        }
    }

    fun header(context: Context): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, HEADER_HEIGHT_DP)
            )
        }
    }

    fun divider(context: Context): View {
        return View(context).apply {
            setBackgroundColor(divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            )
        }
    }

    fun roundedBackground(
        context: Context,
        color: Int,
        radiusDp: Int = CARD_RADIUS_DP
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
    }

    fun cardBackground(
        context: Context,
        radiusDp: Int = CARD_RADIUS_DP
    ): GradientDrawable {
        return roundedBackground(context, surface, radiusDp)
    }

    fun overlayBackground(
        context: Context,
        radiusDp: Int = OVERLAY_RADIUS_DP
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(overlay)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
    }

    fun fieldBackground(
        context: Context,
        radiusDp: Int = 8
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(field)
            cornerRadius = dp(context, radiusDp).toFloat()
        }
    }

    fun styleDialog(dialog: Dialog) {
        val window = dialog.window ?: return
        window.setBackgroundDrawable(overlayBackground(dialog.context, 16))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.32f)
    }

    fun clearDialogBackground(dialog: Dialog) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.28f)
    }
}
