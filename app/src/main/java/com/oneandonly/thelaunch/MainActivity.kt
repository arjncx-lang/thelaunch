package com.oneandonly.thelaunch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private var lastIconShape: String? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { viewModel.refreshApps() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Seed from current prefs so the first onResume() (which fires right after onCreate)
        // doesn't see a "change" and force a redundant second icon reload right after the
        // ViewModel's own init already loaded everything once.
        val settingsPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        lastIconShape = "${settingsPrefs.getString("icon_shape", "none")}|${settingsPrefs.getFloat("icon_scale", 1.0f)}"

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
        val btnAddShortcut = findViewById<ImageView>(R.id.btnAddShortcut)
        val btnSearch = findViewById<ImageView>(R.id.btnSearch)

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
            val spin = ObjectAnimator.ofFloat(it, View.ROTATION, it.rotation, it.rotation + 360f).apply {
                duration = 700
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
            etSearch.text?.clear()
            val job = viewModel.refreshApps(forceReload = true)
            lifecycleScope.launch {
                // refreshApps() often finishes in under a frame (no network involved), which would
                // cancel the animator before it ever renders — force at least one visible rotation.
                job.join()
                delay(700)
                spin.cancel()
            }
        }

        btnAddShortcut.setOnClickListener { showAddShortcutDialog() }

        btnSearch.setOnClickListener {
            if (etSearch.visibility == View.VISIBLE) closeSearch(etSearch) else openSearch(etSearch)
        }

        // The X (drawableEnd) closes the search bar; without also hiding the keyboard here,
        // the EditText stays focused-but-invisible and the keyboard stays stuck open.
        etSearch.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableEnd = etSearch.compoundDrawables[2]
                if (drawableEnd != null && event.rawX >= etSearch.right - etSearch.paddingEnd - drawableEnd.bounds.width()) {
                    closeSearch(etSearch)
                    return@setOnTouchListener true
                }
            }
            false
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

        requestedOrientation = if (prefs.getString("orientation_lock", "none") == "portrait")
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Returning to the launcher (e.g. after opening an app from search) should land on the
        // plain app-grid screen, not leave the search bar open from before we navigated away.
        val etSearch = findViewById<EditText>(R.id.etSearch)
        if (etSearch.visibility == View.VISIBLE) closeSearch(etSearch)

        val searchEnabled = prefs.getBoolean("search_enabled", true)
        findViewById<ImageView>(R.id.btnSearch).visibility = if (searchEnabled) View.VISIBLE else View.GONE

        // Icon shape/scale live in a separate ViewModel instance's preference (SettingsActivity),
        // so a change there won't retroactively repaint icons already cached here — force a
        // reload if either changed since we were last resumed.
        val iconAppearance = "${prefs.getString("icon_shape", "none")}|${prefs.getFloat("icon_scale", 1.0f)}"
        val iconAppearanceChanged = iconAppearance != lastIconShape
        lastIconShape = iconAppearance

        viewModel.refreshApps(forceReload = iconAppearanceChanged)

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

    private fun openSearch(etSearch: EditText) {
        etSearch.visibility = View.VISIBLE
        etSearch.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch(etSearch: EditText) {
        etSearch.text?.clear()
        etSearch.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.visibility = View.GONE
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
            add("Hide App")
            if (app.isShortcut) add("Delete Shortcut") else add("App Info")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, i ->
                when (options[i]) {
                    "Open" -> viewModel.launchApp(app)
                    "Remove from Dock", "Add to Dock" -> viewModel.toggleFavorite(app.packageName)
                    "Hide App" -> viewModel.hideApp(app)
                    "Delete Shortcut" -> viewModel.deleteShortcut(app)
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

    private fun showAddShortcutDialog() {
        val density = resources.displayMetrics.density
        var fetchedIcon: android.graphics.Bitmap? = null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (16 * density).toInt(), (20 * density).toInt(), 0)
        }

        val ivPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = (12 * density).toInt()
            }
            setImageBitmap(viewModel.defaultWebIcon())
        }
        container.addView(ivPreview)

        val etUrl = EditText(this).apply {
            hint = "Website URL (e.g. amazon.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(etUrl)

        val btnFetch = TextView(this).apply {
            text = "Fetch icon"
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_light))
        }
        container.addView(btnFetch)

        val etTitle = EditText(this).apply {
            hint = "Title for this shortcut"
        }
        container.addView(etTitle)

        btnFetch.setOnClickListener {
            val url = etUrl.text?.toString()?.trim().orEmpty()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val spin = ObjectAnimator.ofFloat(btnFetch, View.ALPHA, 1f, 0.3f, 1f).apply {
                duration = 500
                repeatCount = ValueAnimator.INFINITE
                start()
            }
            lifecycleScope.launch {
                val bmp = viewModel.fetchFavicon(url)
                spin.cancel()
                btnFetch.alpha = 1f
                if (bmp != null) {
                    fetchedIcon = bmp
                    ivPreview.setImageBitmap(bmp)
                } else {
                    Toast.makeText(this@MainActivity, "Couldn't fetch icon, using default", Toast.LENGTH_SHORT).show()
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Add website shortcut")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val url = etUrl.text?.toString()?.trim().orEmpty()
                if (url.isBlank()) return@setPositiveButton
                val title = etTitle.text?.toString()?.trim()?.ifBlank { url } ?: url
                viewModel.addShortcut(title, url, fetchedIcon ?: viewModel.defaultWebIcon())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}
