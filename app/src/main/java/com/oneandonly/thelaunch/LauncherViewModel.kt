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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserManager
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

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    private val launcherApps =
        application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager =
        application.getSystemService(Context.USER_SERVICE) as UserManager

    private val iconPx = (52 * application.resources.displayMetrics.density).toInt()

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
        val myPkg = getApplication<Application>().packageName
        val myUser = Process.myUserHandle()
        val density = getApplication<Application>().resources.displayMetrics.densityDpi
        // Read shape/scale once per refresh instead of once per icon — refreshApps() can iterate
        // hundreds of installed activities.
        val settingsPrefs = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val shape = settingsPrefs.getString("icon_shape", "none")
        val scale = settingsPrefs.getFloat("icon_scale", 1.0f)
        val cornerRadius = settingsPrefs.getFloat("icon_corner_radius", 0.2f)
        // Cache must be keyed per-activity, not per-package: some packages (e.g. Google/Gemini,
        // Amazon/Amazon Pay) expose multiple launcher activities under the same packageName, and
        // collapsing them onto one cache key caused duplicate icons and launched the wrong activity.
        val cache = if (forceReload) emptyMap()
            else _installedApps.value.associateBy { it.id }

        _installedApps.value = userManager.userProfiles.flatMap { userHandle ->
            try {
                launcherApps.getActivityList(null, userHandle).mapNotNull { info ->
                    if (info.applicationInfo.packageName == myPkg) return@mapNotNull null
                    val key = "${info.applicationInfo.packageName}|${info.componentName.className}|$userHandle"
                    cache[key] ?: AppInfo(
                        label = info.label.toString(),
                        packageName = info.applicationInfo.packageName,
                        activityName = info.componentName.className,
                        icon = applyIconShape(applyIconScale(rawIconBitmap(info.getIcon(density)), scale), shape, cornerRadius),
                        userHandle = userHandle,
                        isWorkProfile = userHandle != myUser
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

    // AdaptiveIconDrawable.draw() clips itself to the device's current system icon-mask shape
    // (circle/squircle/rounded-square/etc) regardless of what the app actually shipped. Drawing
    // the background/foreground layers directly onto the canvas bypasses that OS-imposed mask
    // and shows the icon exactly as the app packaged it.
    private fun rawIconBitmap(drawable: Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable) {
            drawable.background?.apply { setBounds(0, 0, iconPx, iconPx); draw(canvas) }
            drawable.foreground?.apply { setBounds(0, 0, iconPx, iconPx); draw(canvas) }
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
        val output = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val newSize = iconPx * scale
        val offset = (iconPx - newSize) / 2f
        val destRect = RectF(offset, offset, offset + newSize, offset + newSize)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, destRect, paint)
        return output
    }

    private fun applyIconShape(
        bitmap: Bitmap,
        shape: String? = currentIconShape(),
        cornerRadius: Float = currentCornerRadius()
    ): Bitmap {
        if (shape != "round" && shape != "square") return bitmap
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        if (shape == "round") {
            canvas.drawOval(rect, paint)
        } else {
            val radiusPx = cornerRadius.coerceIn(0f, 0.5f) * (bitmap.width / 2f)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
        }
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    private fun settingsPrefs() = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun currentIconScale() = settingsPrefs().getFloat("icon_scale", 1.0f)
    private fun currentIconShape() = settingsPrefs().getString("icon_shape", "none")
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
            icon = applyIconShape(applyIconScale(icon)),
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
                    icon = applyIconShape(applyIconScale(icon ?: defaultWebIcon())),
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
