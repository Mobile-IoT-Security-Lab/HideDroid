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
        val isAlreadyTracked: Boolean,
        // null = not tracked yet, true = tracked with repackaging, false = tracked without repackaging.
        val isRepackaged: Boolean? = null,
        // Set only for !isInstalled entries backed by a _signed.apk already sitting in the output folder.
        val signedApkPath: String? = null
)