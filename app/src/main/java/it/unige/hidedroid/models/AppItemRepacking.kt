package it.unige.hidedroid.models

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

data class AppItemRepacking (
        val packageName: String,
        val icon: Drawable,
        val name:String?,
        val appInfo: ApplicationInfo,
        val status: String,
        val isInstalled: Boolean,
        val isAlreadyTracked: Boolean
)