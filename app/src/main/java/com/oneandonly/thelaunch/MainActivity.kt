package com.oneandonly.thelaunch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val viewModel by lazy { ViewModelProvider(this)[LauncherViewModel::class.java] }
    private lateinit var adapter: AppAdapter
    private var allApps: List<AppInfo> = emptyList()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { viewModel.refreshApps() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val clockSection = findViewById<LinearLayout>(R.id.clockSection)
        val tvTime = findViewById<TextView>(R.id.tvTime)
        val tvDate = findViewById<TextView>(R.id.tvDate)
        val etSearch = findViewById<EditText>(R.id.etSearch)
        val rvApps = findViewById<RecyclerView>(R.id.rvApps)
        val llDock = findViewById<LinearLayout>(R.id.llDock)
        val btnSettings = findViewById<ImageView>(R.id.btnSettings)
        val btnRefresh = findViewById<ImageView>(R.id.btnRefresh)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            clockSection.setPadding(0, sb.top + 8.dp, 0, 8.dp)
            llDock.setPadding(8.dp, 4.dp, 8.dp, nb.bottom + 4.dp)
            rvApps.setPadding(8.dp, 8.dp, 8.dp, nb.bottom + 8.dp)
            insets
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnRefresh.setOnClickListener {
            it.animate().rotationBy(360f).setDuration(400).start()
            etSearch.text?.clear()
            viewModel.refreshApps(forceReload = true)
        }

        // Clock loop — respects show_time pref
        lifecycleScope.launch {
            while (true) {
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val showTime = prefs.getBoolean("show_time", false)
                if (showTime) {
                    tvTime.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                    tvTime.visibility = View.VISIBLE
                } else {
                    tvTime.visibility = View.GONE
                }
                tvDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
                delay(30_000)
            }
        }

        adapter = AppAdapter(
            onClick = { viewModel.launchApp(it) },
            onLongClick = { showAppMenu(it) }
        )
        rvApps.layoutManager = GridLayoutManager(this, 4)
        rvApps.adapter = adapter
        rvApps.setHasFixedSize(true)
        rvApps.itemAnimator = null

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                adapter.submit(if (q.isBlank()) allApps else allApps.filter { it.label.contains(q, true) })
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        lifecycleScope.launch {
            viewModel.apps.collect { apps ->
                allApps = apps
                val q = etSearch.text?.toString() ?: ""
                adapter.submit(if (q.isBlank()) apps else apps.filter { it.label.contains(q, true) })
                updateDock(llDock, apps, viewModel.favorites.value)
            }
        }

        lifecycleScope.launch {
            viewModel.favorites.collect { favs ->
                updateDock(llDock, allApps, favs)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val tvTime = findViewById<TextView>(R.id.tvTime)
        val showTime = prefs.getBoolean("show_time", false)
        if (showTime) {
            tvTime.text = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            tvTime.visibility = View.VISIBLE
        } else {
            tvTime.visibility = View.GONE
        }

        val root = findViewById<LinearLayout>(R.id.root)
        root.setBackgroundColor(
            if (prefs.getBoolean("show_wallpaper", true)) 0xDD000000.toInt() else 0xFF000000.toInt()
        )

        viewModel.refreshApps()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        viewModel.refreshApps()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
    }

    private fun updateDock(dock: LinearLayout, apps: List<AppInfo>, favorites: List<String>) {
        dock.removeAllViews()
        val favApps = favorites.mapNotNull { pkg -> apps.find { it.packageName == pkg } }.take(5)
        if (favApps.isEmpty()) { dock.visibility = View.GONE; return }
        dock.visibility = View.VISIBLE

        val iconPx = (52 * resources.displayMetrics.density).toInt()
        val padPx = (10 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        favApps.forEach { app ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = params
                setPadding(4.dp, padPx, 4.dp, padPx)
                setOnClickListener { viewModel.launchApp(app) }
                setOnLongClickListener { showAppMenu(app); true }
            }
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
            }
            ImageView(this).apply {
                setImageBitmap(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(iconPx, iconPx)
                frame.addView(this)
            }
            if (app.isWorkProfile) {
                val badgePx = (14 * resources.displayMetrics.density).toInt()
                TextView(this).apply {
                    text = "W"
                    textSize = 8f
                    gravity = Gravity.CENTER
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundResource(R.drawable.badge_circle)
                    layoutParams = FrameLayout.LayoutParams(badgePx, badgePx, Gravity.BOTTOM or Gravity.END)
                    frame.addView(this)
                }
            }
            col.addView(frame)
            TextView(this).apply {
                text = app.label
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = 4.dp }
                col.addView(this)
            }
            dock.addView(col)
        }
    }

    private fun showAppMenu(app: AppInfo) {
        val isFav = viewModel.isFavorite(app.packageName)
        val title = app.label + if (app.isWorkProfile) " (Work)" else ""
        val options = buildList {
            add("Open")
            add(if (isFav) "Remove from Dock" else "Add to Dock")
            add("App Info")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, i ->
                when (options[i]) {
                    "Open" -> viewModel.launchApp(app)
                    "Remove from Dock", "Add to Dock" -> viewModel.toggleFavorite(app.packageName)
                    "App Info" -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", app.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
            .show()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
