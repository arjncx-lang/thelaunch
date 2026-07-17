package com.oneandonly.thelaunch

import android.graphics.Bitmap
import android.os.UserHandle

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Bitmap,
    val userHandle: UserHandle,
    val isWorkProfile: Boolean = false,
    val isShortcut: Boolean = false,
    val shortcutUrl: String? = null,
    /** Virtual entry mixed into Apple Watch honeycomb (not a real installed app). */
    val isLauncherSettings: Boolean = false
) {
    val id: String get() = "$packageName|$activityName|$userHandle"
}
