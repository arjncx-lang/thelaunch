package com.oneandonly.thelaunch

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    private val launcherApps =
        application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager =
        application.getSystemService(Context.USER_SERVICE) as UserManager

    /**
     * Raster size for every app icon bitmap.
     *
     * Must be large enough for Apple Watch zoom + fish-eye (center icons can be
     * ~54dp × 2.4 × 1.18 ≈ 153dp). Rendering at ~52dp and upscaling later is what
     * made icons look soft/blurry. Cap at 512px to keep memory reasonable.
     */
    private val iconPx: Int = run {
        val density = application.resources.displayMetrics.density
        (128f * density).toInt().coerceIn(256, 512)
    }

    /** Ask the system for high-DPI icon assets (xxxhdpi or better when available). */
    private val iconLoadDpi: Int = run {
        val dpi = application.resources.displayMetrics.densityDpi
        min(dpi * 2, DisplayMetrics.DENSITY_XXXHIGH * 2)
    }

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _shortcuts = MutableStateFlow<List<AppInfo>>(emptyList())
    private val _hiddenIds = MutableStateFlow<List<String>>(emptyList())

    val apps: StateFlow<List<AppInfo>> = combine(_installedApps, _shortcuts, _hiddenIds) { installed, shortcuts, hidden ->
        (installed + shortcuts)
            .filterNot { hidden.contains(it.id) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.isWorkProfile }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hiddenApps: StateFlow<List<AppInfo>> = combine(_installedApps, _shortcuts, _hiddenIds) { installed, shortcuts, hidden ->
        (installed + shortcuts)
            .filter { hidden.contains(it.id) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.isWorkProfile }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites

    private val shortcutIconDir: File by lazy {
        File(application.filesDir, "shortcut_icons").apply { mkdirs() }
    }

    init {
        loadFavorites()
        loadShortcuts()
        loadHidden()
        refreshApps()
    }

    fun refreshApps(forceReload: Boolean = false): Job = viewModelScope.launch(Dispatchers.IO) {
        // SettingsActivity uses a separate ViewModel instance; re-read prefs so hide/unhide
        // (and dock favorites) made there show up immediately when we return to the home grid.
        loadHidden()
        loadFavorites()

        val myPkg = getApplication<Application>().packageName
        val myUser = Process.myUserHandle()
        // Read shape/scale once per refresh instead of once per icon — refreshApps() can iterate
        // hundreds of installed activities.
        val settingsPrefs = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val shape = settingsPrefs.getString("icon_shape", "square").let {
            if (it == "round" || it == "square" || it == "hexagon" || it == "original") it else "square"
        }
        val scale = settingsPrefs.getFloat("icon_scale", 1.0f)
        val cornerRadius = settingsPrefs.getFloat("icon_corner_radius", 0.2f)
        // Cache must be keyed per-activity, not per-package: some packages (e.g. Google/Gemini,
        // Amazon/Amazon Pay) expose multiple launcher activities under the same packageName, and
        // collapsing them onto one cache key caused duplicate icons and launched the wrong activity.
        // Also drop cache when icon pixel size changes (upgrade path from the old 52dp bitmaps).
        val cache = if (forceReload) emptyMap()
            else _installedApps.value.associateBy { it.id }
                .filterValues { it.icon.width >= iconPx && it.icon.height >= iconPx }

        _installedApps.value = userManager.userProfiles.flatMap { userHandle ->
            try {
                launcherApps.getActivityList(null, userHandle).mapNotNull { info ->
                    if (info.applicationInfo.packageName == myPkg) return@mapNotNull null
                    val key = "${info.applicationInfo.packageName}|${info.componentName.className}|$userHandle"
                    cache[key] ?: AppInfo(
                        label = info.label.toString(),
                        packageName = info.applicationInfo.packageName,
                        activityName = info.componentName.className,
                        icon = applyIconShape(
                            applyIconScale(
                                rawIconBitmap(
                                    info.getIcon(iconLoadDpi),
                                    // Original = glyph only: no adaptive background plate, no mask.
                                    foregroundOnly = shape == "original"
                                ),
                                scale
                            ),
                            shape,
                            cornerRadius
                        ),
                        userHandle = userHandle,
                        isWorkProfile = userHandle != myUser
                    )
                }
            } catch (_: Exception) { emptyList() }
        }

        // Shortcut icons are cached in _shortcuts and only rebuilt here (not by loadShortcuts,
        // which only runs once at startup) — otherwise a shape/zoom change in Settings would
        // repaint every installed app's icon but leave shortcuts stuck on their old look.
        if (forceReload) reshapeShortcuts(shape, cornerRadius)
    }

    private fun reshapeShortcuts(shape: String?, cornerRadius: Float) {
        _shortcuts.value = _shortcuts.value.map { shortcut ->
            val iconFile = File(shortcutIconDir, "${shortcut.packageName}.png")
            val rawIcon = if (iconFile.exists()) BitmapFactory.decodeFile(iconFile.absolutePath) else defaultWebIcon()
            shortcut.copy(icon = applyIconShape(normalizeIconSize(rawIcon), shape, cornerRadius))
        }
    }

    /**
     * Rasterize a launcher icon into [iconPx].
     *
     * Adaptive icons normally have a solid **background** plate + **foreground** glyph.
     * - Default: draw both (full adaptive art) without the OS squircle mask.
     * - [foregroundOnly] (Original shape): draw only the foreground on a transparent
     *   canvas — no background color plate and no shape mask later.
     */
    private fun rawIconBitmap(drawable: Drawable, foregroundOnly: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        // DENSITY_NONE → ImageView/canvas treat pixels 1:1; we size the view in dp ourselves.
        bmp.density = Bitmap.DENSITY_NONE
        // Leave transparent — no fill — so Original mode has no plate behind the glyph.
        val canvas = Canvas(bmp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            drawable.setBounds(0, 0, iconPx, iconPx)
            if (!foregroundOnly) {
                drawable.background?.apply { setBounds(0, 0, iconPx, iconPx); draw(canvas) }
            }
            // Prefer foreground-only art; if missing, fall back to full drawable.
            val fg = drawable.foreground
            if (fg != null) {
                fg.setBounds(0, 0, iconPx, iconPx)
                fg.draw(canvas)
            } else if (foregroundOnly) {
                drawable.setBounds(0, 0, iconPx, iconPx)
                drawable.draw(canvas)
            }
        } else {
            drawable.setBounds(0, 0, iconPx, iconPx)
            drawable.draw(canvas)
        }
        return bmp
    }

    // Always re-composes into an iconPx canvas — even at 100% zoom — so every icon (installed
    // app or fetched favicon of arbitrary size) ends up pixel-uniform instead of favicons staying
    // at whatever size the network happened to return.
    private fun applyIconScale(bitmap: Bitmap, scale: Float = currentIconScale()): Bitmap {
        if (scale == 1f && bitmap.width == iconPx && bitmap.height == iconPx) {
            return bitmap
        }
        val output = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        output.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(output)
        val newSize = iconPx * scale
        val offset = (iconPx - newSize) / 2f
        val destRect = RectF(offset, offset, offset + newSize, offset + newSize)
        // FILTER_BITMAP only helps when downscaling a higher-res source into the cell.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, destRect, paint)
        return output
    }

    // Resizes to iconPx without applying the user's zoom factor — used for shortcut favicons.
    private fun normalizeIconSize(bitmap: Bitmap): Bitmap = applyIconScale(bitmap, 1.0f)

    private fun applyIconShape(
        bitmap: Bitmap,
        shape: String? = currentIconShape(),
        cornerRadius: Float = currentCornerRadius()
    ): Bitmap {
        // "original" = foreground glyph only, transparent, no mask (see rawIconBitmap).
        if (shape == "original" || (shape != "round" && shape != "square" && shape != "hexagon")) {
            return bitmap
        }
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        output.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        when (shape) {
            "round" -> canvas.drawOval(rect, paint)
            "hexagon" -> canvas.drawPath(flatTopHexagonPath(bitmap.width.toFloat()), paint)
            else -> {
                val radiusPx = cornerRadius.coerceIn(0f, 0.5f) * (bitmap.width / 2f)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            }
        }
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    /** Flat-top regular hexagon inscribed in a size×size square (Apple Clean Mode). */
    private fun flatTopHexagonPath(size: Float): Path {
        val path = Path()
        val cx = size / 2f
        val cy = size / 2f
        // Slight inset keeps anti-aliased edges from clipping against the bitmap bounds.
        val r = size / 2f - 0.5f
        for (i in 0 until 6) {
            // Flat-top: start at -30° so left/right edges are vertical.
            val angle = Math.PI / 3.0 * i - Math.PI / 6.0
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun settingsPrefs() = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun currentIconScale() = settingsPrefs().getFloat("icon_scale", 1.0f)
    private fun currentIconShape() = settingsPrefs().getString("icon_shape", "square").let {
        if (it == "round" || it == "square" || it == "hexagon" || it == "original") it else "square"
    }
    private fun currentCornerRadius() = settingsPrefs().getFloat("icon_corner_radius", 0.2f)

    fun toggleFavorite(packageName: String) {
        val list = _favorites.value.toMutableList()
        if (list.contains(packageName)) list.remove(packageName) else list.add(packageName)
        _favorites.value = list
        prefs.edit().putString("favorites", list.joinToString(",")).apply()
    }

    fun isFavorite(packageName: String) = _favorites.value.contains(packageName)

    fun hideApp(app: AppInfo) {
        if (_hiddenIds.value.contains(app.id)) return
        _hiddenIds.value = _hiddenIds.value + app.id
        persistHidden()
        if (isFavorite(app.packageName)) toggleFavorite(app.packageName)
    }

    fun unhideApp(app: AppInfo) {
        _hiddenIds.value = _hiddenIds.value - app.id
        persistHidden()
    }

    fun launchApp(app: AppInfo) {
        try {
            if (app.isShortcut) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(app.shortcutUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            } else {
                launcherApps.startMainActivity(
                    ComponentName(app.packageName, app.activityName),
                    app.userHandle, null, null
                )
            }
        } catch (_: Exception) {}
    }

    suspend fun fetchFavicon(url: String): Bitmap? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val normalized = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            val host = Uri.parse(normalized).host ?: return@withContext null
            conn = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun defaultWebIcon(): Bitmap {
        val drawable = ContextCompat.getDrawable(getApplication(), R.drawable.ic_web)!!
        return drawable.toBitmap(iconPx, iconPx)
    }

    fun addShortcut(title: String, url: String, icon: Bitmap) {
        val normalized = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val id = "shortcut_${System.currentTimeMillis()}"
        val iconFile = File(shortcutIconDir, "$id.png")
        try {
            FileOutputStream(iconFile).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {}

        val shortcut = AppInfo(
            label = title,
            packageName = id,
            activityName = "",
            // Shortcuts follow the icon shape setting but not the zoom slider — favicons are
            // small, flat images with no adaptive-icon safe-zone padding, so zooming them the
            // same way as app icons just crops the logo.
            icon = applyIconShape(normalizeIconSize(icon)),
            userHandle = Process.myUserHandle(),
            isWorkProfile = false,
            isShortcut = true,
            shortcutUrl = normalized
        )
        _shortcuts.value = _shortcuts.value + shortcut
        persistShortcuts()
    }

    fun deleteShortcut(app: AppInfo) {
        if (!app.isShortcut) return
        _shortcuts.value = _shortcuts.value.filterNot { it.packageName == app.packageName }
        File(shortcutIconDir, "${app.packageName}.png").delete()
        if (isFavorite(app.packageName)) toggleFavorite(app.packageName)
        persistShortcuts()
    }

    private fun persistShortcuts() {
        val arr = JSONArray()
        _shortcuts.value.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.packageName)
                put("title", s.label)
                put("url", s.shortcutUrl)
            })
        }
        prefs.edit().putString("shortcuts", arr.toString()).apply()
    }

    private fun loadShortcuts() {
        val raw = prefs.getString("shortcuts", null) ?: return
        try {
            val arr = JSONArray(raw)
            val myUser = Process.myUserHandle()
            val loaded = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val iconFile = File(shortcutIconDir, "$id.png")
                val icon = if (iconFile.exists()) BitmapFactory.decodeFile(iconFile.absolutePath) else null
                AppInfo(
                    label = obj.getString("title"),
                    packageName = id,
                    activityName = "",
                    icon = applyIconShape(normalizeIconSize(icon ?: defaultWebIcon())),
                    userHandle = myUser,
                    isWorkProfile = false,
                    isShortcut = true,
                    shortcutUrl = obj.getString("url")
                )
            }
            _shortcuts.value = loaded
        } catch (_: Exception) {}
    }

    private fun loadFavorites() {
        val saved = prefs.getString("favorites", "") ?: ""
        _favorites.value = if (saved.isBlank()) emptyList()
        else saved.split(",").filter { it.isNotBlank() }
    }

    private fun persistHidden() = prefs.edit().putString("hidden_apps", _hiddenIds.value.joinToString(",")).apply()

    private fun loadHidden() {
        val saved = prefs.getString("hidden_apps", "") ?: ""
        _hiddenIds.value = if (saved.isBlank()) emptyList()
        else saved.split(",").filter { it.isNotBlank() }
    }
}
