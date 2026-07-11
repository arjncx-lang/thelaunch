package com.oneandonly.thelaunch

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
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
