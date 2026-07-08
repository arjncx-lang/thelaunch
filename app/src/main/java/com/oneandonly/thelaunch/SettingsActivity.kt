package com.oneandonly.thelaunch

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.LinearLayout

class SettingsActivity : AppCompatActivity() {

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

        val switchPortraitLock = findViewById<Switch>(R.id.switchPortraitLock)
        val switchLandscapeLock = findViewById<Switch>(R.id.switchLandscapeLock)
        val orientationLock = prefs.getString("orientation_lock", "none")
        switchPortraitLock.isChecked = orientationLock == "portrait"
        switchLandscapeLock.isChecked = orientationLock == "landscape"
        switchPortraitLock.setOnCheckedChangeListener { _, checked ->
            if (checked) switchLandscapeLock.isChecked = false
            prefs.edit().putString("orientation_lock", if (checked) "portrait" else "none").apply()
        }
        switchLandscapeLock.setOnCheckedChangeListener { _, checked ->
            if (checked) switchPortraitLock.isChecked = false
            prefs.edit().putString("orientation_lock", if (checked) "landscape" else "none").apply()
        }

        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion.text = try {
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"
        } catch (_: Exception) { "" }

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
