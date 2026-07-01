package com.oneandonly.thelaunch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    private val launcherApps =
        application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager =
        application.getSystemService(Context.USER_SERVICE) as UserManager

    private val iconPx = (52 * application.resources.displayMetrics.density).toInt()

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _favorites = MutableStateFlow<List<String>>(emptyList())
    val favorites: StateFlow<List<String>> = _favorites

    init {
        loadFavorites()
        refreshApps()
    }

    fun refreshApps(forceReload: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val myPkg = getApplication<Application>().packageName
            val myUser = Process.myUserHandle()
            val density = getApplication<Application>().resources.displayMetrics.densityDpi
            val cache = if (forceReload) emptyMap()
                else _apps.value.associateBy { it.packageName + it.userHandle.toString() }

            _apps.value = userManager.userProfiles.flatMap { userHandle ->
                try {
                    launcherApps.getActivityList(null, userHandle).mapNotNull { info ->
                        if (info.applicationInfo.packageName == myPkg) return@mapNotNull null
                        val key = info.applicationInfo.packageName + userHandle.toString()
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
            }.sortedWith(compareBy({ it.label.lowercase() }, { it.isWorkProfile }))
        }
    }

    fun toggleFavorite(packageName: String) {
        val list = _favorites.value.toMutableList()
        if (list.contains(packageName)) list.remove(packageName) else list.add(packageName)
        _favorites.value = list
        prefs.edit().putString("favorites", list.joinToString(",")).apply()
    }

    fun isFavorite(packageName: String) = _favorites.value.contains(packageName)

    fun launchApp(app: AppInfo) {
        try {
            launcherApps.startMainActivity(
                android.content.ComponentName(app.packageName, app.activityName),
                app.userHandle, null, null
            )
        } catch (_: Exception) {}
    }

    private fun loadFavorites() {
        val saved = prefs.getString("favorites", "") ?: ""
        _favorites.value = if (saved.isBlank()) emptyList()
        else saved.split(",").filter { it.isNotBlank() }
    }
}
