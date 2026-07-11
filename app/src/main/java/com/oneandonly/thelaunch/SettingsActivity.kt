package com.oneandonly.thelaunch

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.LinearLayout

class SettingsActivity : AppCompatActivity() {

    private val viewModel by lazy { ViewModelProvider(this)[LauncherViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        WindowCompat.setDecorFitsSystemWindows(window, false)

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

        val switchTime = findViewById<Switch>(R.id.switchTime)
        switchTime.isChecked = prefs.getBoolean("show_time", false)
        switchTime.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_time", checked).apply()
        }

        val switchWallpaper = findViewById<Switch>(R.id.switchWallpaper)
        switchWallpaper.isChecked = prefs.getBoolean("show_wallpaper", true)
        switchWallpaper.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_wallpaper", checked).apply()
        }

        val switchSearch = findViewById<Switch>(R.id.switchSearch)
        switchSearch.isChecked = prefs.getBoolean("search_enabled", true)
        switchSearch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("search_enabled", checked).apply()
        }

        val switchPortraitLock = findViewById<Switch>(R.id.switchPortraitLock)
        switchPortraitLock.isChecked = prefs.getString("orientation_lock", "none") == "portrait"
        switchPortraitLock.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putString("orientation_lock", if (checked) "portrait" else "none").apply()
        }

        val rgIconShape = findViewById<RadioGroup>(R.id.rgIconShape)
        rgIconShape.check(if (prefs.getString("icon_shape", "none") == "round") R.id.rbRound else R.id.rbNoCrop)
        rgIconShape.setOnCheckedChangeListener { _, checkedId ->
            val shape = if (checkedId == R.id.rbRound) "round" else "none"
            prefs.edit().putString("icon_shape", shape).apply()
            viewModel.refreshApps(forceReload = true)
        }

        setupIconScale(prefs)

        val llHiddenApps = findViewById<LinearLayout>(R.id.llHiddenApps)
        val tvNoHiddenApps = findViewById<TextView>(R.id.tvNoHiddenApps)
        lifecycleScope.launch {
            viewModel.hiddenApps.collect { hidden ->
                renderHiddenApps(llHiddenApps, tvNoHiddenApps, hidden)
            }
        }

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = try {
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) { "" }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        requestedOrientation = if (prefs.getString("orientation_lock", "none") == "portrait")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // Mirrors LauncherViewModel's icon pipeline (raw compose, then scale) so the slider preview
    // matches exactly what the app grid will show, using the launcher's own adaptive icon as the sample.
    private fun setupIconScale(prefs: android.content.SharedPreferences) {
        val previewPx = (40 * resources.displayMetrics.density).toInt()
        val previewDrawable = packageManager.getApplicationIcon(packageName)
        val rawPreview = composeRaw(previewDrawable, previewPx)

        val ivPreview = findViewById<ImageView>(R.id.ivIconScalePreview)
        val tvValue = findViewById<TextView>(R.id.tvIconScaleValue)
        val seek = findViewById<SeekBar>(R.id.seekIconScale)

        fun render(progress: Int) {
            val scale = 0.5f + progress / 100f
            tvValue.text = "${(scale * 100).toInt()}%"
            ivPreview.setImageBitmap(scaleBitmap(rawPreview, previewPx, scale))
        }

        val savedScale = prefs.getFloat("icon_scale", 1.0f)
        val initialProgress = ((savedScale - 0.5f) * 100).toInt().coerceIn(0, 100)
        seek.progress = initialProgress
        render(initialProgress)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                render(progress)
                prefs.edit().putFloat("icon_scale", 0.5f + progress / 100f).apply()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                viewModel.refreshApps(forceReload = true)
            }
        })
    }

    private fun composeRaw(drawable: android.graphics.drawable.Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            drawable.background?.apply { setBounds(0, 0, size, size); draw(canvas) }
            drawable.foreground?.apply { setBounds(0, 0, size, size); draw(canvas) }
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

    private fun renderHiddenApps(container: LinearLayout, emptyLabel: TextView, hidden: List<AppInfo>) {
        container.removeAllViews()
        emptyLabel.visibility = if (hidden.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

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
