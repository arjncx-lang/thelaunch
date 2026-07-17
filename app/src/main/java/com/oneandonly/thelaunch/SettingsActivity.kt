package com.oneandonly.thelaunch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.LinearLayout
import kotlin.math.cos
import kotlin.math.sin

class SettingsActivity : AppCompatActivity() {

    private val viewModel by lazy { ViewModelProvider(this)[LauncherViewModel::class.java] }
    /** Hidden apps list stays collapsed until the user expands the header. */
    private var hiddenAppsExpanded = false
    /** About body stays collapsed until the user expands the header. */
    private var aboutExpanded = false
    private var lastHiddenApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.enableEdgeToEdge(window)
        // Match home: fully transparent 3-button / gesture bar (no contrast scrim).
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        window.decorView.post {
            listOf(android.R.id.navigationBarBackground, android.R.id.statusBarBackground).forEach { id ->
                window.decorView.findViewById<View>(id)?.let { bar ->
                    bar.setBackgroundColor(Color.TRANSPARENT)
                    bar.background = null
                    bar.alpha = 0f
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
        }

        val root = findViewById<LinearLayout>(R.id.settingsContent)
        val toolbar = findViewById<LinearLayout>(R.id.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            toolbar.setPadding(0, sb.top + 8.dp, 0, 0)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, nb.bottom + 32.dp)
            insets
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val switchSearch = findViewById<Switch>(R.id.switchSearch)
        switchSearch.isChecked = prefs.getBoolean("search_enabled", true)
        switchSearch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("search_enabled", checked).apply()
        }

        val switchAlphabet = findViewById<Switch>(R.id.switchAlphabet)
        switchAlphabet.isChecked = prefs.getBoolean("alphabet_enabled", true)
        switchAlphabet.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("alphabet_enabled", checked).apply()
        }

        // Apple Watch Mode: honeycomb layout on home (layout-only; not an icon mask).
        val switchWatchMode = findViewById<Switch>(R.id.switchAppleClean)
        switchWatchMode.isChecked = prefs.getBoolean("apple_clean_mode", false)
        switchWatchMode.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("apple_clean_mode", checked).apply()
        }

        val switchShowAppNames = findViewById<Switch>(R.id.switchShowAppNames)
        switchShowAppNames.isChecked = prefs.getBoolean("show_app_names", true)
        switchShowAppNames.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_app_names", checked).apply()
        }

        val switchShowTime = findViewById<Switch>(R.id.switchShowTime)
        switchShowTime.isChecked = prefs.getBoolean("show_time", true)
        switchShowTime.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_time", checked).apply()
        }

        val switchShowStatusBar = findViewById<Switch>(R.id.switchShowStatusBar)
        switchShowStatusBar.isChecked = prefs.getBoolean("show_status_bar", true)
        switchShowStatusBar.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_status_bar", checked).apply()
        }

        val switchShowRefresh = findViewById<Switch>(R.id.switchShowRefresh)
        switchShowRefresh.isChecked = prefs.getBoolean("show_refresh", true)
        switchShowRefresh.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_refresh", checked).apply()
        }

        val switchShowAdd = findViewById<Switch>(R.id.switchShowAdd)
        switchShowAdd.isChecked = prefs.getBoolean("show_add", true)
        switchShowAdd.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_add", checked).apply()
        }

        // Apple whole-cluster zoom is pinch / double-tap on the honeycomb only (no Settings slider).

        // Icon Closeness (Apple Watch): SeekBar 0..100 → spacing 1.55 (far) … 0.90 (tight).
        val seekCloseness = findViewById<SeekBar>(R.id.seekHoneycombCloseness)
        val tvCloseness = findViewById<TextView>(R.id.tvHoneycombClosenessValue)
        val closeness = prefs.getFloat("honeycomb_closeness", 0.55f).coerceIn(0f, 1f)
        seekCloseness.progress = (closeness * 100).toInt().coerceIn(0, 100)
        fun closenessLabel(progress: Int): String = when {
            progress < 25 -> "Loose"
            progress < 50 -> "Roomy"
            progress < 75 -> "Normal"
            progress < 90 -> "Tight"
            else -> "Packed"
        }
        tvCloseness.text = closenessLabel(seekCloseness.progress)
        seekCloseness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvCloseness.text = closenessLabel(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putFloat("honeycomb_closeness", sb.progress / 100f).apply()
            }
        })

        // Grid columns: SeekBar 0..3 → 3..6 columns (default 4)
        val seekGrid = findViewById<SeekBar>(R.id.seekGridColumns)
        val tvGrid = findViewById<TextView>(R.id.tvGridColumnsValue)
        val columns = prefs.getInt("grid_columns", 4).coerceIn(3, 6)
        seekGrid.progress = columns - 3
        tvGrid.text = columns.toString()
        seekGrid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvGrid.text = (progress + 3).toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("grid_columns", sb.progress + 3).apply()
            }
        })

        // Label size: SeekBar 0..8 → 9..17 sp (default 11)
        val seekLabel = findViewById<SeekBar>(R.id.seekLabelSize)
        val tvLabel = findViewById<TextView>(R.id.tvLabelSizeValue)
        val labelSp = prefs.getFloat("label_text_size", 11f).coerceIn(9f, 17f)
        seekLabel.progress = (labelSp - 9f).toInt().coerceIn(0, 8)
        tvLabel.text = "${labelSp.toInt()} sp"
        seekLabel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvLabel.text = "${progress + 9} sp"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putFloat("label_text_size", (sb.progress + 9).toFloat()).apply()
            }
        })

        setupIconAppearance(prefs)

        val llHiddenApps = findViewById<LinearLayout>(R.id.llHiddenApps)
        val rowHiddenHeader = findViewById<LinearLayout>(R.id.rowHiddenAppsHeader)
        val tvHiddenHeader = findViewById<TextView>(R.id.tvHiddenAppsHeader)
        val tvHiddenCount = findViewById<TextView>(R.id.tvHiddenAppsCount)
        val tvHiddenChevron = findViewById<TextView>(R.id.tvHiddenAppsChevron)

        rowHiddenHeader.setOnClickListener {
            if (lastHiddenApps.isEmpty()) return@setOnClickListener
            hiddenAppsExpanded = !hiddenAppsExpanded
            applyHiddenAppsExpandState(llHiddenApps, tvHiddenHeader, tvHiddenChevron)
            if (hiddenAppsExpanded) {
                renderHiddenApps(llHiddenApps, lastHiddenApps)
            }
        }

        lifecycleScope.launch {
            viewModel.hiddenApps.collect { hidden ->
                lastHiddenApps = hidden
                updateHiddenAppsHeader(tvHiddenHeader, tvHiddenCount, tvHiddenChevron, hidden)
                if (hidden.isEmpty()) {
                    hiddenAppsExpanded = false
                    llHiddenApps.removeAllViews()
                    llHiddenApps.visibility = View.GONE
                } else if (hiddenAppsExpanded) {
                    renderHiddenApps(llHiddenApps, hidden)
                    llHiddenApps.visibility = View.VISIBLE
                } else {
                    llHiddenApps.visibility = View.GONE
                }
            }
        }

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        val versionLabel = try {
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) { "" }
        tvVersion.text = versionLabel.ifEmpty { "About" }

        val rowAboutHeader = findViewById<LinearLayout>(R.id.rowAboutHeader)
        val llAboutContent = findViewById<LinearLayout>(R.id.llAboutContent)
        val tvAboutChevron = findViewById<TextView>(R.id.tvAboutChevron)
        rowAboutHeader.setOnClickListener {
            aboutExpanded = !aboutExpanded
            llAboutContent.visibility = if (aboutExpanded) View.VISIBLE else View.GONE
            tvAboutChevron.text = if (aboutExpanded) "▾" else "▸"
        }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    // Mirrors LauncherViewModel's icon pipeline (raw compose, then scale, then shape mask) so the
    // preview matches exactly what the app grid will show, using the launcher's own adaptive icon
    // as the sample. Shape, zoom and corner radius all feed the same preview image.
    private fun setupIconAppearance(prefs: android.content.SharedPreferences) {
        val previewPx = (40 * resources.displayMetrics.density).toInt()
        val previewDrawable = try {
            packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            ContextCompat.getDrawable(this, R.drawable.ic_web)!!
        }
        val rgIconShape = findViewById<RadioGroup>(R.id.rgIconShape)
        val rowCornerRadius = findViewById<LinearLayout>(R.id.rowCornerRadius)
        val ivPreview = findViewById<ImageView>(R.id.ivIconScalePreview)
        val tvScaleValue = findViewById<TextView>(R.id.tvIconScaleValue)
        val seekScale = findViewById<SeekBar>(R.id.seekIconScale)
        val tvRadiusValue = findViewById<TextView>(R.id.tvCornerRadiusValue)
        val seekRadius = findViewById<SeekBar>(R.id.seekCornerRadius)

        fun currentShape(): String = when (rgIconShape.checkedRadioButtonId) {
            R.id.rbRound -> "round"
            R.id.rbHexagon -> "hexagon"
            R.id.rbOriginal -> "original"
            else -> "square"
        }

        fun isKnownShape(s: String?) =
            s == "round" || s == "square" || s == "hexagon" || s == "original"

        fun render() {
            val shape = currentShape()
            val scale = 0.5f + seekScale.progress / 100f
            val radiusFraction = seekRadius.progress / 100f
            tvScaleValue.text = "${(scale * 100).toInt()}%"
            tvRadiusValue.text = "${seekRadius.progress}%"
            // Original recomposes without adaptive background so preview matches the grid.
            val raw = composeRaw(previewDrawable, previewPx, foregroundOnly = shape == "original")
            val scaled = scaleBitmap(raw, previewPx, scale)
            ivPreview.setImageBitmap(maskBitmap(scaled, shape, radiusFraction))
        }

        val savedShape = prefs.getString("icon_shape", "square")
        when (savedShape) {
            "round" -> rgIconShape.check(R.id.rbRound)
            "hexagon" -> rgIconShape.check(R.id.rbHexagon)
            "original" -> rgIconShape.check(R.id.rbOriginal)
            else -> rgIconShape.check(R.id.rbSquare)
        }
        // Migrate unknown legacy values to square (keep "original").
        if (!isKnownShape(savedShape)) {
            prefs.edit().putString("icon_shape", "square").apply()
        }
        rowCornerRadius.visibility = if (currentShape() == "square") View.VISIBLE else View.GONE

        val savedScale = prefs.getFloat("icon_scale", 1.0f)
        seekScale.progress = ((savedScale - 0.5f) * 100).toInt().coerceIn(0, 100)

        val savedRadius = prefs.getFloat("icon_corner_radius", 0.2f)
        seekRadius.progress = (savedRadius * 100).toInt().coerceIn(0, 50)

        render()

        rgIconShape.setOnCheckedChangeListener { _, _ ->
            rowCornerRadius.visibility = if (currentShape() == "square") View.VISIBLE else View.GONE
            render()
            prefs.edit().putString("icon_shape", currentShape()).apply()
            viewModel.refreshApps(forceReload = true)
        }

        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putFloat("icon_scale", 0.5f + sb.progress / 100f).apply()
                viewModel.refreshApps(forceReload = true)
            }
        })

        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = render()
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putFloat("icon_corner_radius", sb.progress / 100f).apply()
                viewModel.refreshApps(forceReload = true)
            }
        })
    }

    /** @param foregroundOnly skip adaptive background plate (Original shape). */
    private fun composeRaw(
        drawable: android.graphics.drawable.Drawable,
        size: Int,
        foregroundOnly: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            drawable.setBounds(0, 0, size, size)
            if (!foregroundOnly) {
                drawable.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
            }
            val fg = drawable.foreground
            if (fg != null) {
                fg.setBounds(0, 0, size, size)
                fg.draw(canvas)
            } else if (foregroundOnly) {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            }
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        return bmp
    }

    private fun scaleBitmap(bitmap: Bitmap, size: Int, scale: Float): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val newSize = size * scale
        val offset = (size - newSize) / 2f
        val destRect = RectF(offset, offset, offset + newSize, offset + newSize)
        canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return output
    }

    private fun maskBitmap(bitmap: Bitmap, shape: String, cornerRadiusFraction: Float): Bitmap {
        // Original = raw app art / PNG, no shape mask.
        if (shape == "original" || (shape != "round" && shape != "square" && shape != "hexagon")) {
            return bitmap
        }
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        when (shape) {
            "round" -> canvas.drawOval(rect, paint)
            "hexagon" -> canvas.drawPath(flatTopHexagonPath(bitmap.width.toFloat()), paint)
            else -> {
                val radiusPx = cornerRadiusFraction.coerceIn(0f, 0.5f) * (bitmap.width / 2f)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            }
        }
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun flatTopHexagonPath(size: Float): Path {
        val path = Path()
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - 0.5f
        for (i in 0 until 6) {
            val angle = Math.PI / 3.0 * i - Math.PI / 6.0
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun updateHiddenAppsHeader(
        header: TextView,
        countLabel: TextView,
        chevron: TextView,
        hidden: List<AppInfo>
    ) {
        if (hidden.isEmpty()) {
            header.text = "Expand hidden apps"
            countLabel.text = "No hidden apps"
            chevron.visibility = View.GONE
            header.alpha = 0.55f
        } else {
            header.text = if (hiddenAppsExpanded) "Collapse hidden apps" else "Expand hidden apps"
            countLabel.text = if (hidden.size == 1) "1 hidden app" else "${hidden.size} hidden apps"
            chevron.visibility = View.VISIBLE
            chevron.text = if (hiddenAppsExpanded) "▾" else "▸"
            header.alpha = 1f
        }
    }

    private fun applyHiddenAppsExpandState(
        container: LinearLayout,
        header: TextView,
        chevron: TextView
    ) {
        header.text = if (hiddenAppsExpanded) "Collapse hidden apps" else "Expand hidden apps"
        chevron.text = if (hiddenAppsExpanded) "▾" else "▸"
        container.visibility = if (hiddenAppsExpanded) View.VISIBLE else View.GONE
    }

    private fun renderHiddenApps(container: LinearLayout, hidden: List<AppInfo>) {
        container.removeAllViews()

        val iconPx = (36 * resources.displayMetrics.density).toInt()
        hidden.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0x22FFFFFF)
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = 1.dp }
            }
            ImageView(this).apply {
                setImageBitmap(app.icon)
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx).also { it.marginEnd = 12.dp }
                row.addView(this)
            }
            TextView(this).apply {
                text = app.label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(this)
            }
            TextView(this).apply {
                text = "Show"
                setTextColor(0xFF64B5F6.toInt())
                textSize = 14f
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                setOnClickListener { viewModel.unhideApp(app) }
                row.addView(this)
            }
            container.addView(row)
        }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
