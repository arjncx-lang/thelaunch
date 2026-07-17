package com.oneandonly.thelaunch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
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
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val viewModel by lazy { ViewModelProvider(this)[LauncherViewModel::class.java] }
    private lateinit var adapter: AppAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var alphabetBar: AlphabetIndexBar
    private lateinit var alphabetBubble: TextView
    private lateinit var rvApps: RecyclerView
    private lateinit var watchHoneycomb: WatchHoneycombView
    private var allApps: List<AppInfo> = emptyList()
    private var lastIconShape: String? = null
    private var labelTextSizeSp: Float = 11f
    private var alphabetEnabled: Boolean = true
    /** Settings toggle: Apple Watch honeycomb vs normal grid. */
    private var watchMode: Boolean = false
    private var showAppNames: Boolean = true
    private var bubbleTargetY: Float = 0f
    private var bubbleYAnim: ValueAnimator? = null
    private var bubbleHideAnim: ObjectAnimator? = null
    private var lastScrubLetter: Char? = null
    /** Cached virtual Settings tile used only in Apple Watch honeycomb. */
    private var settingsHoneycombApp: AppInfo? = null
    /** Live nav-bar height so dock / grid can pad without a solid spacer strip. */
    private var navBarInsetPx: Int = 0
    /** Settings → Show Status Bar (default on). */
    private var showStatusBar: Boolean = true
    private lateinit var llDock: LinearLayout

    private val appleEase = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

    private val highlightClearHandler = Handler(Looper.getMainLooper())
    private val clearHighlightRunnable = Runnable {
        adapter.setHighlightLetter(null)
        alphabetBar.clearActive()
        hideAlphabetBubble()
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { viewModel.refreshApps() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Seed from current prefs so the first onResume() (which fires right after onCreate)
        // doesn't see a "change" and force a redundant second icon reload right after the
        // ViewModel's own init already loaded everything once.
        lastIconShape = iconAppearanceFingerprint()

        // One-shot HD icon rebuild after upgrading from the old low-res (52dp) pipeline.
        val settings = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (settings.getInt("icon_raster_version", 0) < 2) {
            settings.edit().putInt("icon_raster_version", 2).apply()
            // Force ViewModel to drop any in-memory low-res bitmaps on first frame after upgrade.
            lastIconShape = "hd-upgrade"
        }

        applyTransparentSystemBars()

        val root = findViewById<LinearLayout>(R.id.root)
        val clockSection = findViewById<LinearLayout>(R.id.clockSection)
        val ivDayNight = findViewById<ImageView>(R.id.ivDayNight)
        val tvAmPm = findViewById<TextView>(R.id.tvAmPm)
        val tvDate = findViewById<TextView>(R.id.tvDate)
        val etSearch = findViewById<EditText>(R.id.etSearch)
        rvApps = findViewById(R.id.rvApps)
        watchHoneycomb = findViewById(R.id.watchHoneycomb)
        llDock = findViewById(R.id.llDock)
        val btnSettings = findViewById<ImageView>(R.id.btnSettings)
        val btnRefresh = findViewById<ImageView>(R.id.btnRefresh)
        val btnAddShortcut = findViewById<ImageView>(R.id.btnAddShortcut)
        val btnSearch = findViewById<ImageView>(R.id.btnSearch)
        alphabetBar = findViewById(R.id.alphabetBar)
        alphabetBubble = findViewById(R.id.tvAlphabetBubble)

        // Edge-to-edge: black home paints under the system nav buttons (transparent bar).
        // Only interactive chrome is padded so taps stay above the 3-button / gesture strip —
        // same pattern other apps use for "transparent navigation buttons".
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            navBarInsetPx = nb.bottom.takeIf { it > 0 } ?: systemNavBarHeightPx()
            clockSection.setPadding(0, sb.top + 8.dp, 0, 8.dp)
            applyBottomChromeInsets()
            // End padding keeps icons clear of the A-Z strip when it is visible (grid mode only).
            val endPad = if (alphabetEnabled && !watchMode) 32.dp else 8.dp
            // Bottom padding so the last grid row / honeycomb edge clears the buttons;
            // clipToPadding=false lets content scroll/draw under the transparent bar.
            // Grid: pad bottom so last row isn't under the buttons (clipToPadding=false → scrolls under).
            // Honeycomb draws full-bleed so icons can sit behind the transparent nav like other apps.
            val contentBottom = if (llDock.visibility == View.VISIBLE) 8.dp else 8.dp + navBarInsetPx
            rvApps.setPadding(8.dp, 8.dp, endPad, contentBottom)
            alphabetBar.setPadding(0, 4.dp, 0, contentBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

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
                Toast.makeText(this@MainActivity, "Refreshed", Toast.LENGTH_SHORT).show()
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

        // Date + white sun/moon with AM/PM. No clock — status bar already shows time.
        lifecycleScope.launch {
            while (true) {
                updateDateAndDayNight(tvDate, ivDayNight, tvAmPm)
                delay(60_000)
            }
        }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val columns = prefs.getInt("grid_columns", 4).coerceIn(3, 6)
        labelTextSizeSp = prefs.getFloat("label_text_size", 11f).coerceIn(9f, 17f)
        alphabetEnabled = prefs.getBoolean("alphabet_enabled", true)
        // Same key as Settings → Apple Watch Mode switch (on/off only).
        watchMode = prefs.getBoolean("apple_clean_mode", false)
        showAppNames = prefs.getBoolean("show_app_names", true)

        adapter = AppAdapter(
            onClick = { viewModel.launchApp(it) },
            onLongClick = { showAppMenu(it) }
        )
        adapter.setLabelTextSizeSp(labelTextSizeSp)
        adapter.setShowLabels(showAppNames)
        adapter.setGridColumns(columns)

        layoutManager = GridLayoutManager(this, columns)
        rvApps.layoutManager = layoutManager
        rvApps.adapter = adapter
        rvApps.setHasFixedSize(true)
        rvApps.itemAnimator = null
        rvApps.overScrollMode = View.OVER_SCROLL_NEVER

        // In Apple Watch mode, Settings is a honeycomb tile (not the top-bar gear).
        watchHoneycomb.listener = object : WatchHoneycombView.Listener {
            override fun onAppClick(app: AppInfo) {
                if (app.isLauncherSettings) openLauncherSettings() else viewModel.launchApp(app)
            }
            override fun onAppLongClick(app: AppInfo) {
                if (app.isLauncherSettings) openLauncherSettings() else showAppMenu(app)
            }
            override fun onClusterZoomChanged(zoom: Float) {
                // Persist pinch / double-tap whole-cluster zoom for Apple mode.
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("honeycomb_zoom", zoom.coerceIn(0.45f, 2.4f))
                    .apply()
            }
        }
        applyHoneycombAppearance(prefs)
        applyChromeVisibility(prefs)
        applyHomeLayoutMode()

        alphabetBar.listener = object : AlphabetIndexBar.Listener {
            override fun onLetterSelected(letter: Char, centerYInParent: Float) {
                highlightClearHandler.removeCallbacks(clearHighlightRunnable)
                adapter.setHighlightLetter(letter)
                showAlphabetBubble(letter, centerYInParent)

                val index = adapter.indexOfFirstLetter(letter)
                if (index >= 0) {
                    // Instant section pin while finger moves (keeps bar + grid locked together).
                    layoutManager.scrollToPositionWithOffset(index, 0)
                }
                lastScrubLetter = letter
            }

            override fun onScrubEnd() {
                // Keep highlight briefly so the user sees the section they landed on.
                highlightClearHandler.removeCallbacks(clearHighlightRunnable)
                highlightClearHandler.postDelayed(clearHighlightRunnable, 700)
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                val filtered = if (q.isBlank()) allApps else allApps.filter { it.label.contains(q, true) }
                submitAppsToHome(filtered, searching = q.isNotBlank())
                if (q.isNotBlank()) {
                    adapter.setHighlightLetter(null)
                    alphabetBar.clearActive()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        lifecycleScope.launch {
            viewModel.apps.collect { apps ->
                allApps = apps
                val q = etSearch.text?.toString() ?: ""
                val filtered = if (q.isBlank()) apps else apps.filter { it.label.contains(q, true) }
                submitAppsToHome(filtered, searching = q.isNotBlank())
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
        // HOME / Taskbar can re-assert contrast when returning from another app — re-apply.
        applyTransparentSystemBars()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        updateDateAndDayNight(
            findViewById(R.id.tvDate),
            findViewById(R.id.ivDayNight),
            findViewById(R.id.tvAmPm)
        )

        // Returning to the launcher (e.g. after opening an app from search) should land on the
        // plain app-grid screen, not leave the search bar open from before we navigated away.
        val etSearch = findViewById<EditText>(R.id.etSearch)
        if (etSearch.visibility == View.VISIBLE) closeSearch(etSearch)

        applyChromeVisibility(prefs)

        val newAlphabetEnabled = prefs.getBoolean("alphabet_enabled", true)
        if (newAlphabetEnabled != alphabetEnabled) {
            alphabetEnabled = newAlphabetEnabled
            if (!alphabetEnabled) {
                adapter.setHighlightLetter(null)
                alphabetBar.clearActive()
                hideAlphabetBubble()
            }
            // Recalculate grid end padding for the A-Z strip.
            ViewCompat.requestApplyInsets(findViewById(R.id.root))
        }
        val etSearchVisible = findViewById<EditText>(R.id.etSearch).visibility == View.VISIBLE
        updateAlphabetBarVisibility(searching = etSearchVisible)

        // Apply layout prefs from Settings (columns / label size / names / watch mode).
        val columns = prefs.getInt("grid_columns", 4).coerceIn(3, 6)
        if (layoutManager.spanCount != columns) {
            layoutManager.spanCount = columns
        }
        // Labels scale with column count so denser grids keep names readable.
        adapter.setGridColumns(columns)
        val newLabelSp = prefs.getFloat("label_text_size", 11f).coerceIn(9f, 17f)
        if (newLabelSp != labelTextSizeSp) {
            labelTextSizeSp = newLabelSp
            adapter.setLabelTextSizeSp(labelTextSizeSp)
        }
        val newShowNames = prefs.getBoolean("show_app_names", true)
        if (newShowNames != showAppNames) {
            showAppNames = newShowNames
            adapter.setShowLabels(showAppNames)
        }
        // Dock labels follow the same toggle; always rebuild so size/visibility stay in sync.
        updateDock(findViewById(R.id.llDock), allApps, viewModel.favorites.value)

        // Icon Zoom + closeness + labels for Apple Watch honeycomb (independent of bitmap reload).
        applyHoneycombAppearance(prefs)

        // Toggle from Settings → Apple Watch Mode. On = honeycomb apps only; Off = normal grid.
        val newWatchMode = prefs.getBoolean("apple_clean_mode", false)
        if (newWatchMode != watchMode) {
            watchMode = newWatchMode
            applyHomeLayoutMode()
            val q = findViewById<EditText>(R.id.etSearch).text?.toString().orEmpty()
            val filtered = if (q.isBlank()) allApps else allApps.filter { it.label.contains(q, true) }
            submitAppsToHome(filtered, searching = q.isNotBlank())
            ViewCompat.requestApplyInsets(findViewById(R.id.root))
        } else {
            applyHomeLayoutMode()
        }

        // Icon shape/scale/radius live in a separate ViewModel instance's preference
        // (SettingsActivity), so a change there won't retroactively repaint icons already cached
        // here - force a reload if any of them changed since we were last resumed.
        val iconAppearance = iconAppearanceFingerprint()
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
        highlightClearHandler.removeCallbacks(clearHighlightRunnable)
        adapter.setHighlightLetter(null)
        alphabetBar.clearActive()
        hideAlphabetBubble()
        bubbleYAnim?.cancel()
        bubbleHideAnim?.cancel()
    }

    private fun iconAppearanceFingerprint(): String {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val shape = prefs.getString("icon_shape", "square").let {
            if (it == "round" || it == "square" || it == "hexagon" || it == "original") it else "square"
        }
        return "$shape|${prefs.getFloat("icon_scale", 1.0f)}|${prefs.getFloat("icon_corner_radius", 0.2f)}"
    }

    /**
     * Match other apps: fully transparent status + 3-button / gesture nav bar so
     * home content shows through behind the system buttons (no grey contrast scrim).
     *
     * Device is on 3-button nav (navigation_mode=0). Android 15+ keeps that bar
     * translucent unless [Window.isNavigationBarContrastEnforced] is false.
     * [WindowCompat.enableEdgeToEdge] alone leaves the 3-button scrim on — we clear it.
     * Also clear the decor [android.R.id.navigationBarBackground] view some OEMs keep painted.
     */
    private fun applyTransparentSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.enableEdgeToEdge(window)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            // Critical for 3-button: without this the system paints a semi-opaque strip.
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
        // Decor scrim views are recreated after edge-to-edge; clear them on the next frame.
        window.decorView.post { clearDecorSystemBarBackgrounds() }
        applyStatusBarVisibility()
    }

    /**
     * Hide/show the system status bar from Settings.
     * When hidden: immersive sticky — swipe down from the top edge to peek at
     * notifications/clock, then it auto-hides again (nav bar stays visible).
     */
    private fun applyStatusBarVisibility() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        showStatusBar = prefs.getBoolean("show_status_bar", true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    /** Make PhoneWindow's status/nav background views fully transparent (or gone). */
    private fun clearDecorSystemBarBackgrounds() {
        val decor = window.decorView
        listOf(
            android.R.id.navigationBarBackground,
            android.R.id.statusBarBackground
        ).forEach { id ->
            decor.findViewById<View>(id)?.let { bar ->
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

    /**
     * Keep dock icons above the system buttons using **margin**, not padding.
     * Padding would extend the dock's tinted background into the nav area and look
     * like a solid bar; margin leaves only the black root under the transparent buttons.
     */
    private fun applyBottomChromeInsets() {
        if (!::llDock.isInitialized) return
        llDock.setPadding(8.dp, 4.dp, 8.dp, 4.dp)
        val lp = llDock.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.bottomMargin != navBarInsetPx) {
            lp.bottomMargin = navBarInsetPx
            llDock.layoutParams = lp
        }
    }

    /**
     * Home-bar chrome toggles (search / refresh / add / date-time / status bar).
     * Settings gear stays on the top bar in grid mode; in Apple Watch mode it is
     * mixed into the honeycomb as an app icon so it does not use toolbar space.
     */
    private fun applyChromeVisibility(prefs: android.content.SharedPreferences) {
        val searchEnabled = prefs.getBoolean("search_enabled", true)
        findViewById<ImageView>(R.id.btnSearch).visibility =
            if (searchEnabled) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.btnRefresh).visibility =
            if (prefs.getBoolean("show_refresh", true)) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.btnAddShortcut).visibility =
            if (prefs.getBoolean("show_add", true)) View.VISIBLE else View.GONE

        // Toolbar gear only outside Apple Watch mode.
        findViewById<ImageView>(R.id.btnSettings).visibility =
            if (watchMode) View.GONE else View.VISIBLE

        val showTime = prefs.getBoolean("show_time", true)
        val timeVis = if (showTime) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.ivDayNight).visibility = timeVis
        findViewById<TextView>(R.id.tvAmPm).visibility = timeVis
        findViewById<TextView>(R.id.tvDate).visibility = timeVis

        applyStatusBarVisibility()
    }

    private fun openLauncherSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Virtual Settings tile for the Apple Watch honeycomb (center of the spiral).
     * Not a real package — click opens [SettingsActivity].
     */
    private fun settingsHoneycombEntry(): AppInfo {
        settingsHoneycombApp?.let { return it }
        val entry = AppInfo(
            label = "Settings",
            packageName = "$packageName.launcher_settings",
            activityName = "LauncherSettings",
            icon = createSettingsHoneycombIcon(),
            userHandle = Process.myUserHandle(),
            isLauncherSettings = true
        )
        settingsHoneycombApp = entry
        return entry
    }

    /** App-icon style gear for the honeycomb (circular gray tile + white settings glyph). */
    private fun createSettingsHoneycombIcon(): Bitmap {
        val size = (128f * resources.displayMetrics.density).toInt().coerceIn(256, 512)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3C.toInt() }
        val r = size / 2f
        canvas.drawCircle(r, r, r, bg)
        val gear = ContextCompat.getDrawable(this, R.drawable.ic_settings)!!.mutate()
        val pad = (size * 0.22f).toInt()
        gear.setBounds(pad, pad, size - pad, size - pad)
        gear.setTint(0xFFFFFFFF.toInt())
        gear.draw(canvas)
        return bmp
    }

    /**
     * Apple mode layout:
     * - [honeycomb_zoom] = whole-cluster zoom (pinch / double-tap on home; not in Settings)
     * - [honeycomb_closeness] = relative packing only (0 loose … 1 packed)
     * - app names / label size
     *
     * Grid "Icon Zoom" ([icon_scale]) is separate — it crops art inside each cell.
     * Apple mode uses whole-icon zoom so icons grow/shrink as complete circles.
     */
    private fun applyHoneycombAppearance(prefs: android.content.SharedPreferences) {
        // Prefer dedicated honeycomb zoom; fall back once to grid icon_scale for older prefs.
        val clusterZoom = if (prefs.contains("honeycomb_zoom")) {
            prefs.getFloat("honeycomb_zoom", 1f)
        } else {
            prefs.getFloat("icon_scale", 1f)
        }.coerceIn(0.45f, 2.4f)
        val closeness = prefs.getFloat("honeycomb_closeness", 0.55f).coerceIn(0f, 1f)
        // Higher closeness → smaller spacing factor (icons closer together).
        val spacingFactor = 1.55f - closeness * (1.55f - 0.90f)
        val names = prefs.getBoolean("show_app_names", true)
        val labelSp = prefs.getFloat("label_text_size", 11f).coerceIn(9f, 17f)
        watchHoneycomb.setAppearance(
            clusterZoom = clusterZoom,
            spacingFactor = spacingFactor,
            showLabels = names,
            labelTextSizeSp = labelSp
        )
    }

    /**
     * Switches between normal app grid and Apple Watch honeycomb.
     * In watch mode Settings is a honeycomb app icon; in grid mode the top-bar gear is used.
     */
    private fun applyHomeLayoutMode() {
        if (watchMode) {
            rvApps.visibility = View.GONE
            watchHoneycomb.visibility = View.VISIBLE
            alphabetBar.visibility = View.GONE
            hideAlphabetBubble()
            adapter.setHighlightLetter(null)
            alphabetBar.clearActive()
        } else {
            rvApps.visibility = View.VISIBLE
            watchHoneycomb.visibility = View.GONE
            updateAlphabetBarVisibility(
                searching = findViewById<EditText>(R.id.etSearch).visibility == View.VISIBLE
            )
        }
        // Keep toolbar gear visibility in sync with mode.
        findViewById<ImageView>(R.id.btnSettings).visibility =
            if (watchMode) View.GONE else View.VISIBLE
    }

    /** Push the filtered app list into whichever home surface is active. */
    private fun submitAppsToHome(apps: List<AppInfo>, searching: Boolean) {
        if (watchMode) {
            // Mix Settings into the honeycomb so it does not occupy the status-bar toolbar row.
            val settings = settingsHoneycombEntry()
            val honeycombApps = if (searching) {
                val q = findViewById<EditText>(R.id.etSearch).text?.toString().orEmpty()
                if (settings.label.contains(q, ignoreCase = true)) listOf(settings) + apps
                else apps
            } else {
                // First node is the spiral center — easy to find after hiding the gear.
                listOf(settings) + apps
            }
            watchHoneycomb.setApps(honeycombApps)
            alphabetBar.visibility = View.GONE
            hideAlphabetBubble()
        } else {
            adapter.submit(apps)
            alphabetBar.setAvailableLetters(apps.map { it.label })
            updateAlphabetBarVisibility(searching = searching)
        }
    }

    /** Fade + scale the floating letter bubble into place (Apple Contacts style). */
    private fun showAlphabetBubble(letter: Char, centerYInParent: Float) {
        bubbleHideAnim?.cancel()
        alphabetBubble.text = letter.toString()
        alphabetBubble.visibility = View.VISIBLE

        val bubbleH = alphabetBubble.height.takeIf { it > 0 }
            ?: (56 * resources.displayMetrics.density).toInt()
        val parentH = (alphabetBar.parent as View).height
        val maxY = (parentH - bubbleH).coerceAtLeast(0).toFloat()
        val targetY = (centerYInParent - bubbleH / 2f).coerceIn(0f, maxY)
        bubbleTargetY = targetY

        // Glide the bubble vertically; short duration tracks the finger without lag.
        val fromY = alphabetBubble.translationY
        if (alphabetBubble.alpha < 0.05f || abs(fromY - targetY) < 1f) {
            alphabetBubble.translationY = targetY
        } else {
            bubbleYAnim?.cancel()
            bubbleYAnim = ValueAnimator.ofFloat(fromY, targetY).apply {
                duration = 70L
                interpolator = appleEase
                addUpdateListener { alphabetBubble.translationY = it.animatedValue as Float }
                start()
            }
        }

        if (alphabetBubble.alpha < 0.95f) {
            alphabetBubble.animate().cancel()
            alphabetBubble.scaleX = 0.72f
            alphabetBubble.scaleY = 0.72f
            alphabetBubble.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(160L)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
        } else if (lastScrubLetter != letter) {
            // Soft pulse when the letter changes while already visible.
            alphabetBubble.animate().cancel()
            alphabetBubble.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(60L)
                .setInterpolator(appleEase)
                .withEndAction {
                    alphabetBubble.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100L)
                        .setInterpolator(appleEase)
                        .start()
                }
                .start()
        }
    }

    private fun hideAlphabetBubble() {
        bubbleYAnim?.cancel()
        bubbleHideAnim?.cancel()
        if (alphabetBubble.visibility != View.VISIBLE) {
            alphabetBubble.alpha = 0f
            return
        }
        alphabetBubble.animate().cancel()
        alphabetBubble.animate()
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(180L)
            .setInterpolator(appleEase)
            .withEndAction {
                alphabetBubble.visibility = View.GONE
                alphabetBubble.scaleX = 1f
                alphabetBubble.scaleY = 1f
            }
            .start()
        lastScrubLetter = null
    }

    /** White sun/moon + AM/PM next to weekday/month. AM is 00:00–11:59, PM is 12:00–23:59. */
    private fun updateDateAndDayNight(tvDate: TextView, ivDayNight: ImageView, tvAmPm: TextView) {
        val now = Date()
        tvDate.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isAm = hour < 12
        tvAmPm.text = if (isAm) "AM" else "PM"
        // Sun for daytime hours, moon for evening/night.
        val isDay = hour in 6..17
        ivDayNight.setImageResource(if (isDay) R.drawable.ic_sun else R.drawable.ic_moon)
        ivDayNight.contentDescription = if (isDay) "Day" else "Night"
    }

    /** Framework navigation_bar_height, used if window insets report 0. */
    private fun systemNavBarHeightPx(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 48.dp
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
        updateAlphabetBarVisibility(searching = false)
    }

    /** Alphabet strip only in grid mode, when enabled, not searching, and letters exist. */
    private fun updateAlphabetBarVisibility(searching: Boolean) {
        val show = !watchMode && alphabetEnabled && !searching && alphabetBar.hasLetters()
        alphabetBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) hideAlphabetBubble()
    }

    private fun updateDock(dock: LinearLayout, apps: List<AppInfo>, favorites: List<String>) {
        dock.removeAllViews()
        val favApps = favorites.mapNotNull { pkg -> apps.find { it.packageName == pkg } }.take(5)
        if (favApps.isEmpty()) {
            dock.visibility = View.GONE
            // Content must absorb nav inset when the dock is hidden.
            ViewCompat.requestApplyInsets(findViewById(R.id.root))
            return
        }
        dock.visibility = View.VISIBLE
        applyBottomChromeInsets()
        ViewCompat.requestApplyInsets(findViewById(R.id.root))

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
            if (showAppNames) {
                TextView(this).apply {
                    text = app.label
                    textSize = labelTextSizeSp
                    setTextColor(0xFFFFFFFF.toInt())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = 4.dp }
                    col.addView(this)
                }
            }
            dock.addView(col)
        }
    }

    private fun showAppMenu(app: AppInfo) {
        if (app.isLauncherSettings) {
            openLauncherSettings()
            return
        }
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
