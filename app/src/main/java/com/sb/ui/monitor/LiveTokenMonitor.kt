package com.sb.ui.monitor

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.sb.backend.core.BackendManager
import com.sb.backend.market.MarketDataManager
import com.sb.backend.market.repo.MarketFeedRepository
import com.sb.backend.models.Instrument
import com.sb.ui.theme.AppDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiveTokenMonitor(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var mainDialog: Dialog? = null
    private var categoryDialog: Dialog? = null
    private var monitorJob: Job? = null
    private var instruments: List<Instrument> = emptyList()
    private var tickKeys: Set<String> = emptySet()
    private var counts = LiveTokenCounts()
    private var activeCategory: LiveTokenCategory? = null
    private var statusView: TextView? = null
    private var withTickCountView: TextView? = null
    private var tokenCountView: TextView? = null
    private var noTokenCountView: TextView? = null
    private var noLivePriceCountView: TextView? = null
    private var categoryCountView: TextView? = null
    private var categoryListView: ListView? = null
    private var lastRenderedCounts: LiveTokenCounts? = null
    private var loadError: String? = null

    fun show() {
        dismiss()

        val dialog = buildMainDialog()
        mainDialog = dialog
        showDialog(dialog, 0.92f, 0.72f)
        startMonitoring()
        refreshInstruments()
    }

    fun dismiss() {
        stopMonitoring()
        categoryDialog?.dismiss()
        categoryDialog = null
        activeCategory = null
        categoryCountView = null
        categoryListView = null
        lastRenderedCounts = null
        mainDialog?.dismiss()
        mainDialog = null
        clearMainViews()
    }

    private fun buildMainDialog(): Dialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppDesign.background)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = AppDesign.text(context, "Live Token", 19f, AppDesign.textPrimary, true)
        title.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        val close = AppDesign.text(context, "✕", 22f, AppDesign.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(2), dp(8))
            setOnClickListener { dismiss() }
        }
        header.addView(title)
        header.addView(close)
        root.addView(header)

        statusView = AppDesign.text(
            context,
            "Loading instruments...",
            11f,
            AppDesign.textMuted,
            false
        ).apply {
            setPadding(0, dp(2), 0, dp(10))
        }
        root.addView(statusView)

        val withTick = categoryCard(
            root,
            LiveTokenCategory.TOKEN_WITH_TICK,
            AppDesign.positive
        ) { view -> withTickCountView = view }
        val token = categoryCard(
            root,
            LiveTokenCategory.TOKEN,
            AppDesign.accent
        ) { view -> tokenCountView = view }
        val noToken = categoryCard(
            root,
            LiveTokenCategory.NO_TOKEN,
            AppDesign.negative
        ) { view -> noTokenCountView = view }
        val noLivePrice = categoryCard(
            root,
            LiveTokenCategory.NO_LIVE_PRICE,
            Color.rgb(251, 146, 60)
        ) { view -> noLivePriceCountView = view }

        withTick.setOnClickListener { showCategory(LiveTokenCategory.TOKEN_WITH_TICK) }
        token.setOnClickListener { showCategory(LiveTokenCategory.TOKEN) }
        noToken.setOnClickListener { showCategory(LiveTokenCategory.NO_TOKEN) }
        noLivePrice.setOnClickListener { showCategory(LiveTokenCategory.NO_LIVE_PRICE) }

        val dialog = Dialog(context)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            if (mainDialog == dialog) {
                stopMonitoring()
                categoryDialog?.dismiss()
                categoryDialog = null
                activeCategory = null
                categoryCountView = null
                categoryListView = null
                lastRenderedCounts = null
                mainDialog = null
                clearMainViews()
            }
        }
        return dialog
    }

    private fun categoryCard(
        root: LinearLayout,
        category: LiveTokenCategory,
        color: Int,
        onCountView: (TextView) -> Unit
    ): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppDesign.cardBackground(context, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(10))
            }
        }

        val label = AppDesign.text(context, category.label, 15f, AppDesign.textPrimary, true)
        val count = AppDesign.text(context, "0", 25f, color, true, true).apply {
            setPadding(0, dp(4), 0, dp(2))
        }
        val hint = AppDesign.text(
            context,
            when (category) {
                LiveTokenCategory.TOKEN_WITH_TICK -> "Live total count"
                LiveTokenCategory.TOKEN -> "Names with status"
                LiveTokenCategory.NO_TOKEN -> "Names with missing details"
                LiveTokenCategory.NO_LIVE_PRICE -> "Missing price reasons"
            },
            10f,
            AppDesign.textMuted,
            false
        )

        label.setOnClickListener { card.performClick() }
        count.setOnClickListener { card.performClick() }
        hint.setOnClickListener { card.performClick() }

        card.addView(label)
        card.addView(count)
        card.addView(hint)
        root.addView(card)
        onCountView(count)
        return card
    }

    private fun showCategory(category: LiveTokenCategory) {
        lastRenderedCounts = null

        categoryDialog?.dismiss()
        activeCategory = category
        val dialog = buildCategoryDialog(category)
        categoryDialog = dialog
        dialog.setOnDismissListener {
            if (categoryDialog == dialog) {
                categoryDialog = null
                activeCategory = null
                categoryCountView = null
                categoryListView = null
                lastRenderedCounts = null
            }
        }

        if (category == LiveTokenCategory.TOKEN_WITH_TICK) {
            showDialog(dialog, 0.86f, 0.42f)
        } else {
            showDialog(dialog, 0.94f, 0.84f)
        }
        renderCategoryDialog()
    }

    private fun buildCategoryDialog(category: LiveTokenCategory): Dialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppDesign.background)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = AppDesign.text(context, category.label, 17f, AppDesign.textPrimary, true)
        title.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        val close = AppDesign.text(context, "✕", 22f, AppDesign.accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(2), dp(8))
            setOnClickListener { dialogForCategory()?.dismiss() }
        }
        header.addView(title)
        header.addView(close)
        root.addView(header)

        val count = AppDesign.text(
            context,
            "",
            16f,
            AppDesign.accent,
            true
        ).apply {
            setPadding(0, dp(4), 0, dp(10))
        }
        categoryCountView = count
        root.addView(count)

        if (category == LiveTokenCategory.TOKEN_WITH_TICK) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = AppDesign.cardBackground(context, 14)
                setPadding(dp(16), dp(18), dp(16), dp(18))
            }
            val total = AppDesign.text(context, "0", 34f, AppDesign.positive, true, true)
            total.gravity = Gravity.CENTER
            val note = AppDesign.text(
                context,
                "Token available and live tick received • updating live",
                11f,
                AppDesign.textMuted,
                false
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            }
            val closeButton = AppDesign.text(
                context,
                "Close",
                13f,
                AppDesign.accent,
                true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
                setOnClickListener { dialogForCategory()?.dismiss() }
            }
            box.addView(total)
            box.addView(note)
            box.addView(closeButton)
            root.addView(box)
            categoryCountView = total
            categoryListView = null
        } else {
            val list = ListView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                divider = android.graphics.drawable.ColorDrawable(AppDesign.divider)
                dividerHeight = 1
                setPadding(0, 0, 0, dp(6))
                clipToPadding = false
            }
            categoryListView = list
            root.addView(
                list,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        val dialog = Dialog(context)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    private fun dialogForCategory(): Dialog? = categoryDialog

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            MarketFeedRepository.observeTicks().collect { ticks ->
                tickKeys = ticks.keys.toSet()
                applySnapshot()
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun refreshInstruments() {
        statusView?.text = "Loading instruments..."
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (!BackendManager.isInitialized()) {
                    emptyList()
                } else {
                    BackendManager.instruments().getActive(50_000)
                }
            }
            loadError = when {
                !BackendManager.isInitialized() -> "Backend is not initialized yet."
                loaded.isEmpty() -> "No active instruments found. Sync scrip master."
                else -> null
            }
            instruments = loaded
            applySnapshot()
        }
    }

    private fun applySnapshot() {
        counts = LiveTokenClassifier.counts(instruments, tickKeys)
        updateMainCounts()
        renderCategoryDialog()
    }

    private fun subscribedKeys(): Set<String> = try {
        MarketDataManager.activeKeys().map { it.key() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun updateMainCounts() {
        withTickCountView?.text = counts.tokenWithTick.toString()
        tokenCountView?.text = counts.token.toString()
        noTokenCountView?.text = counts.noToken.toString()
        noLivePriceCountView?.text = counts.noLivePrice.toString()

        val error = loadError
        statusView?.text = if (error != null) {
            error
        } else {
            "Active instruments: ${counts.total}  •  live tick keys: ${tickKeys.size}" +
                "  •  updating live"
        }
    }

    private fun renderCategoryDialog() {
        val category = activeCategory ?: return
        val countView = categoryCountView ?: return

        countView.text = when (category) {
            LiveTokenCategory.TOKEN_WITH_TICK ->
                "${counts.tokenWithTick} instruments have token and live tick"
            LiveTokenCategory.TOKEN -> "Total ${counts.token} tokens"
            LiveTokenCategory.NO_TOKEN -> "Total ${counts.noToken} no token"
            LiveTokenCategory.NO_LIVE_PRICE ->
                "Total ${counts.noLivePrice} no live price"
        }

        val list = categoryListView ?: return
        if (lastRenderedCounts == counts) {
            return
        }
        lastRenderedCounts = counts

        val rows = LiveTokenClassifier
            .entries(instruments, tickKeys, category, subscribedKeys())
            .map { it.displayRow }
            .ifEmpty {
                listOf(
                    when (category) {
                        LiveTokenCategory.TOKEN -> "No instruments with token."
                        LiveTokenCategory.NO_TOKEN -> "All active instruments have a token."
                        LiveTokenCategory.TOKEN_WITH_TICK -> "No live ticks received yet."
                        LiveTokenCategory.NO_LIVE_PRICE -> "All active instruments have a live price."
                    }
                )
            }

        list.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            rows
        )
    }

    private fun showDialog(dialog: Dialog, widthRatio: Float, heightRatio: Float) {
        dialog.show()
        AppDesign.styleDialog(dialog)
        val metrics = context.resources.displayMetrics
        dialog.window?.apply {
            setGravity(Gravity.CENTER)
            setLayout(
                (metrics.widthPixels * widthRatio).toInt(),
                (metrics.heightPixels * heightRatio).toInt()
            )
        }
    }

    private fun clearMainViews() {
        statusView = null
        withTickCountView = null
        tokenCountView = null
        noTokenCountView = null
        noLivePriceCountView = null
    }

    private fun dp(value: Int): Int = AppDesign.dp(context, value)
}
