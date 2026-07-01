package com.oneandonly.thelaunch

import android.graphics.Bitmap
import android.os.UserHandle

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Bitmap,
    val userHandle: UserHandle,
    val isWorkProfile: Boolean = false
)
