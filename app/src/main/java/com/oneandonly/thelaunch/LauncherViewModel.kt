package com.oneandonly.thelaunch

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
                        icon = info.getIcon(density).toBitmap(iconPx, iconPx),
                        userHandle = userHandle,
                        isWorkProfile = userHandle != myUser
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
    }

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
        try {
            val normalized = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
            val host = Uri.parse(normalized).host ?: return@withContext null
            val conn = URL("https://www.google.com/s2/favicons?sz=128&domain=$host")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
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
            icon = icon,
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
                    icon = icon ?: defaultWebIcon(),
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
