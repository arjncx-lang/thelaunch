package com.oneandonly.thelaunch

import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val onClick: (AppInfo) -> Unit,
    private val onLongClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()
    /** User preference base size (Settings → label size). Scaled by [columns]. */
    private var labelTextSizeSp: Float = 11f
    /** Grid column count (3–6); drives label scale + cell padding. */
    private var columns: Int = 4
    /** Settings → Show App Names (grid mode). */
    private var showLabels: Boolean = true
    /** When non-null, apps whose label starts with this letter are highlighted; others dim. */
    private var highlightLetter: Char? = null

    fun submit(newList: List<AppInfo>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].id == newList[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newList[n]
        })
        items = newList
        diff.dispatchUpdatesTo(this)
    }

    fun setLabelTextSizeSp(sp: Float) {
        if (labelTextSizeSp == sp) return
        labelTextSizeSp = sp
        notifyDataSetChanged()
    }

    fun setGridColumns(cols: Int) {
        val c = cols.coerceIn(3, 6)
        if (columns == c) return
        columns = c
        notifyDataSetChanged()
    }

    fun setShowLabels(show: Boolean) {
        if (showLabels == show) return
        showLabels = show
        notifyDataSetChanged()
    }

    fun setHighlightLetter(letter: Char?) {
        if (highlightLetter == letter) return
        highlightLetter = letter
        notifyDataSetChanged()
    }

    fun getItems(): List<AppInfo> = items

    fun indexOfFirstLetter(letter: Char): Int {
        val target = letter.uppercaseChar()
        return items.indexOfFirst { AlphabetIndexBar.firstLetter(it.label) == target }
    }

    /**
     * Scale the Settings label size to the current grid density.
     * Fewer columns → larger type; more columns → smaller so names fit the cell.
     */
    private fun effectiveLabelSp(): Float {
        val scale = when (columns) {
            3 -> 1.20f
            4 -> 1.00f
            5 -> 0.86f
            6 -> 0.74f
            else -> 1f
        }
        return (labelTextSizeSp * scale).coerceIn(8f, 18f)
    }

    /** Horizontal/vertical cell padding shrinks as columns increase (more room for text). */
    private fun cellPaddingPx(density: Float): Int {
        val dp = when (columns) {
            3 -> 12f
            4 -> 8f
            5 -> 5f
            6 -> 3f
            else -> 8f
        }
        return (dp * density).toInt()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val label: TextView = v.findViewById(R.id.tvLabel)
        val badge: TextView = v.findViewById(R.id.tvBadge)
        val root: View = v
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val app = items[pos]
        val density = h.itemView.resources.displayMetrics.density
        val pad = cellPaddingPx(density)
        h.root.setPadding(pad, pad, pad, pad)

        h.icon.setImageBitmap(app.icon)
        if (showLabels) {
            h.label.visibility = View.VISIBLE
            h.label.text = app.label
            h.label.maxLines = 2
            h.label.ellipsize = TextUtils.TruncateAt.END
            h.label.gravity = android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.CENTER_VERTICAL

            val maxSp = effectiveLabelSp()
            val maxSpInt = maxSp.toInt().coerceIn(8, 18)
            // Fixed 2-line height so autosize can shrink type to fit the cell width
            // (autosize is ignored when height is wrap_content).
            val labelH = (maxSp * density * 1.22f * 2f).toInt().coerceAtLeast((18 * density).toInt())
            val lp = h.label.layoutParams
            if (lp.height != labelH) {
                lp.height = labelH
                h.label.layoutParams = lp
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                h.label,
                /* autoSizeMinTextSize = */ 8,
                /* autoSizeMaxTextSize = */ maxSpInt,
                /* autoSizeStepGranularity = */ 1,
                TypedValue.COMPLEX_UNIT_SP
            )
            h.label.setTextColor(0xFFFFFFFF.toInt())
        } else {
            TextViewCompat.setAutoSizeTextTypeWithDefaults(h.label, TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE)
            h.label.visibility = View.GONE
        }
        h.badge.visibility = if (app.isWorkProfile) View.VISIBLE else View.GONE
        h.icon.elevation = 0f

        val letter = highlightLetter
        if (letter == null) {
            h.root.alpha = 1f
            h.root.setBackgroundColor(0x00000000)
        } else {
            val match = AlphabetIndexBar.firstLetter(app.label) == letter.uppercaseChar()
            if (match) {
                h.root.alpha = 1f
                h.root.setBackgroundColor(0x33FFFFFF)
            } else {
                h.root.alpha = 0.28f
                h.root.setBackgroundColor(0x00000000)
            }
        }

        h.itemView.setOnClickListener { onClick(app) }
        h.itemView.setOnLongClickListener { onLongClick(app); true }
    }

    override fun getItemCount() = items.size
}
