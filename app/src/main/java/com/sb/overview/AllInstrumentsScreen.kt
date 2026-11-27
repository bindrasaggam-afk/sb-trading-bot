package com.sb.overview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sb.backend.core.BackendManager
import com.sb.backend.models.Instrument
import com.sb.ui.theme.AppDesign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AllInstrumentsScreen(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
    private val onInstrumentSelected: (Instrument) -> Unit
) {

    private var loadJob: Job? = null
    private var allItems: List<Instrument> = emptyList()
    private var filteredItems: List<Instrument> = emptyList()
    private var searchText = ""
    private var exchangeFilter = "All"
    private var categoryFilter = "All Categories"
    private var visibleLimit = PAGE_SIZE

    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var listView: RecyclerView
    private lateinit var loadMoreView: TextView
    private lateinit var adapter: AllInstrumentsAdapter
    private val exchangeChips = LinkedHashMap<String, TextView>()
    private val categoryChips = LinkedHashMap<String, TextView>()

    fun createView(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppDesign.background)
            setPadding(dp(12), dp(6), dp(12), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = AppDesign.text(context, "‹", 26f, AppDesign.accent, bold = true)
            .apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), 0, dp(10), dp(2))
                setOnClickListener { onBack() }
            }
        header.addView(back)

        val titleBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        titleBlock.addView(
            AppDesign.text(
                context,
                "All Instruments",
                20f,
                AppDesign.textPrimary,
                bold = true
            )
        )
        countText = AppDesign.text(
            context,
            "Loading instrument master...",
            11f,
            AppDesign.textMuted
        )
        titleBlock.addView(countText)
        header.addView(titleBlock)

        val refresh = AppDesign.text(
            context,
            "Refresh",
            11f,
            AppDesign.accent,
            bold = true
        ).apply {
            setPadding(dp(8), dp(8), dp(2), dp(8))
            setOnClickListener { loadInstruments() }
        }
        header.addView(refresh)
        root.addView(header)

        val search = EditText(context).apply {
            hint = "Search symbol, name, ISIN or token"
            setSingleLine(true)
            textSize = 13f
            setTextColor(AppDesign.textPrimary)
            setHintTextColor(AppDesign.textMuted)
            background = AppDesign.fieldBackground(context, 9)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(6)) }
        }
        search.addTextChangedListener(object : TextWatcher {
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
                searchText = s?.toString()?.trim().orEmpty()
                applyFilters(resetPage = true)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(search)

        root.addView(
            buildChipRow(
                values = InstrumentListFilter.exchanges,
                chips = exchangeChips
            ) { value ->
                exchangeFilter = value
                refreshChipState(exchangeChips, exchangeFilter)
                applyFilters(resetPage = true)
            }
        )

        root.addView(
            buildChipRow(
                values = InstrumentListFilter.categories,
                chips = categoryChips
            ) { value ->
                categoryFilter = value
                refreshChipState(categoryChips, categoryFilter)
                applyFilters(resetPage = true)
            }
        )

        statusText = AppDesign.text(
            context,
            "Instrument master load हो रहा है...",
            11f,
            AppDesign.textMuted
        ).apply {
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(statusText)

        adapter = AllInstrumentsAdapter { instrument ->
            onInstrumentSelected(instrument)
        }

        listView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(true)
            itemAnimator = null
            setBackgroundColor(AppDesign.background)
            clipToPadding = false
            setPadding(0, 0, 0, dp(28))
            adapter = this@AllInstrumentsScreen.adapter
        }
        root.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        loadMoreView = AppDesign.text(
            context,
            "Load more",
            12f,
            AppDesign.accent,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            background = AppDesign.cardBackground(context, 9)
            setPadding(0, dp(10), 0, dp(10))
            visibility = View.GONE
            setOnClickListener {
                visibleLimit += PAGE_SIZE
                applyFilters(resetPage = false)
            }
        }
        root.addView(
            loadMoreView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(2), 0, dp(6)) }
        )

        refreshChipState(exchangeChips, exchangeFilter)
        refreshChipState(categoryChips, categoryFilter)
        loadInstruments()
        return root
    }

    fun release() {
        loadJob?.cancel()
        loadJob = null
    }

    private fun loadInstruments() {
        loadJob?.cancel()
        statusText.text = "Loading all active instruments..."
        loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (!BackendManager.isInitialized()) {
                        return@withContext LoadResult(
                            error = "Backend is still initializing. Tap Refresh."
                        )
                    }
                    val instruments = BackendManager.instruments()
                        .getActive(limit = 20000)
                        .filter { !it.isAlias }
                    LoadResult(instruments = instruments)
                } catch (error: Exception) {
                    LoadResult(
                        error = "Instrument master load failed: " +
                            (error.message ?: "unknown error")
                    )
                }
            }

            val error = result.error
            if (error != null) {
                allItems = emptyList()
                filteredItems = emptyList()
                adapter.submit(emptyList())
                countText.text = "0 instruments"
                statusText.text = error
                loadMoreView.visibility = View.GONE
                return@launch
            }

            allItems = result.instruments
            applyFilters(resetPage = true)
        }
    }

    private fun applyFilters(resetPage: Boolean) {
        if (resetPage) visibleLimit = PAGE_SIZE

        filteredItems = InstrumentListFilter.filter(
            instruments = allItems,
            query = searchText,
            exchange = exchangeFilter,
            category = categoryFilter
        )

        val visible = filteredItems.take(visibleLimit)
        adapter.submit(visible)

        countText.text = buildString {
            append(filteredItems.size)
            append(" of ")
            append(allItems.size)
            append(" instruments")
        }

        statusText.text = when {
            allItems.isEmpty() -> "No instruments available in the local master."
            filteredItems.isEmpty() -> "No instrument matches the selected filters."
            else -> "${filteredItems.size} matching instruments • showing ${visible.size}"
        }

        loadMoreView.visibility =
            if (filteredItems.size > visibleLimit) View.VISIBLE else View.GONE
    }

    private fun buildChipRow(
        values: List<String>,
        chips: LinkedHashMap<String, TextView>,
        onSelected: (String) -> Unit
    ): HorizontalScrollView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(12), dp(2))
        }

        values.forEach { value ->
            val chip = AppDesign.text(
                context,
                value,
                11f,
                AppDesign.textPrimary,
                bold = true
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(6), dp(11), dp(6))
                setOnClickListener { onSelected(value) }
            }
            chips[value] = chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, dp(5), 0) }
            )
        }

        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, dp(10), 0)
            addView(row)
        }
    }

    private fun refreshChipState(
        chips: LinkedHashMap<String, TextView>,
        selected: String
    ) {
        chips.forEach { (value, chip) ->
            val active = value == selected
            chip.setTextColor(
                if (active) Color.BLACK else AppDesign.textPrimary
            )
            chip.background = AppDesign.roundedBackground(
                context,
                if (active) AppDesign.accent else AppDesign.surface,
                8
            )
        }
    }

    private fun dp(value: Int): Int = AppDesign.dp(context, value)

    private data class LoadResult(
        val instruments: List<Instrument> = emptyList(),
        val error: String? = null
    )

    companion object {
        private const val PAGE_SIZE = 200
    }
}

private class AllInstrumentsAdapter(
    private val onItemClick: (Instrument) -> Unit
) : RecyclerView.Adapter<AllInstrumentsAdapter.ItemHolder>() {

    private val items = ArrayList<Instrument>()

    fun submit(newItems: List<Instrument>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ItemHolder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppDesign.cardBackground(context, 10)
            setPadding(dp(context, 12), dp(context, 9), dp(context, 10), dp(context, 9))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 4) }
        }

        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val symbol = AppDesign.text(
            context,
            "",
            14f,
            AppDesign.textPrimary,
            bold = true
        )
        val name = AppDesign.text(
            context,
            "",
            11f,
            AppDesign.textMuted
        ).apply {
            setPadding(0, dp(context, 2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val meta = AppDesign.text(
            context,
            "",
            10f,
            AppDesign.accent
        ).apply { setPadding(0, dp(context, 3), 0, 0) }

        textBlock.addView(symbol)
        textBlock.addView(name)
        textBlock.addView(meta)

        val chevron = AppDesign.text(
            context,
            "›",
            20f,
            AppDesign.accent,
            bold = true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(context, 6), 0, 0, 0)
        }

        row.addView(textBlock)
        row.addView(chevron)

        val holder = ItemHolder(row, symbol, name, meta)
        row.setOnClickListener {
            val position = holder.adapterPosition
            if (position == RecyclerView.NO_POSITION) return@setOnClickListener
            onItemClick(items[position])
        }
        return holder
    }

    override fun onBindViewHolder(holder: ItemHolder, position: Int) {
        val instrument = items[position]
        holder.symbol.text = instrument.symbol
        holder.name.text = instrument.name
        holder.meta.text = buildString {
            append(instrument.exchange)
            if (instrument.category.isNotBlank()) {
                append(" • ")
                append(instrument.category)
            }
            if (instrument.segment.isNotBlank() &&
                !instrument.segment.equals(instrument.category, true)
            ) {
                append(" • ")
                append(instrument.segment)
            }
            append(" • ")
            append(if (instrument.token.isNullOrBlank()) "No token" else "Token ready")
        }
    }

    class ItemHolder(
        view: View,
        val symbol: TextView,
        val name: TextView,
        val meta: TextView
    ) : RecyclerView.ViewHolder(view)

    private fun dp(context: Context, value: Int): Int =
        AppDesign.dp(context, value)
}
