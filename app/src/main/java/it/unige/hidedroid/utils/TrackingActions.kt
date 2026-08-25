package it.unige.hidedroid.utils

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.dave.realmdatahelper.hidedroid.ApplicationStatus
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.task.AbstractTaskWrapper
import it.unige.hidedroid.task.RepackageTask
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracking-mode switches shared between the app list (quick actions) and the
 * per-app modification screen, so both drive the same repack/no-repack state
 * and the mode can be flipped at any time from either place.
 */
object TrackingActions {

    const val DEFAULT_PRIVACY_LEVEL = 1

    private fun rememberSelectedPrivacyLevel(context: Context, packageName: String, privacyLevel: Int) {
        val app = context.applicationContext as HideDroidApplication
        app.selectedPrivacyLevelsLock.lock()
        try {
            val existing = app.selectedPrivacyLevels[packageName]
            if (existing != null) {
                existing.set(privacyLevel)
            } else {
                app.selectedPrivacyLevels[packageName] = AtomicInteger(privacyLevel)
            }
        } finally {
            app.selectedPrivacyLevelsLock.unlock()
        }
    }

    private fun storeStatus(packageName: String, status: ApplicationStatus) {
        val existing = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(packageName)
        if (existing != null) {
            existing.update(status)
            existing.storeStateApp()
        } else {
            status.storeStateApp()
        }
    }

    /**
     * Tracks [packageName] without repackaging it: no APK is touched, so this
     * is instant and safe to call any number of times, in either direction.
     */
    fun trackWithoutRepack(context: Context, packageName: String, privacyLevel: Int = DEFAULT_PRIVACY_LEVEL) {
        PackageNamePrivacyLevel(packageName = packageName, isInstalled = true, privacyLevel = privacyLevel)
                .storePrivacySettings()
        rememberSelectedPrivacyLevel(context, packageName, privacyLevel)
        storeStatus(packageName, ApplicationStatus(packageName = packageName, installed = true, isRepackaged = false))
    }

    /**
     * Drops [packageName] back to "not tracked": deletes its privacy-level and
     * status records and stops anonymizing its traffic. No APK to clean up here
     * (this is only offered from the "No repack" state, which never built one);
     * repacked/installed apps aren't reset since that would leave the resigned
     * APK's install state and this bookkeeping out of sync.
     */
    fun resetTracking(context: Context, packageName: String) {
        PackageNamePrivacyLevel(packageName = packageName).deleteThisFromRealm()
        ApplicationStatus(packageName = packageName).deleteThisFromRealm()

        val app = context.applicationContext as HideDroidApplication
        app.selectedPrivacyLevelsLock.lock()
        try {
            app.selectedPrivacyLevels.remove(packageName)
        } finally {
            app.selectedPrivacyLevelsLock.unlock()
        }
    }

    /**
     * Kicks off decode/build/sign for [apkPath] in the background. RepackageTask
     * reports progress through its own dialog on [activity] and stores the
     * resulting ApplicationStatus/PackageNamePrivacyLevel once done; installing
     * the produced APK is a separate step on the modification screen.
     *
     * @param splitApkPaths any split APKs (config/density/ABI/language) belonging
     *                      to the same app - resigned alongside the base, never decompiled.
     * @param removeCheckLicensePermission strip com.android.vending.CHECK_LICENSE
     *                      from the manifest if present.
     */
    fun startRepack(activity: Activity, packageName: String, apkPath: String, splitApkPaths: List<String> = emptyList(),
                     removeCheckLicensePermission: Boolean = false, onFinished: Runnable? = null) {
        val app = activity.application as HideDroidApplication

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                    activity.getString(R.string.channel_id_notification),
                    activity.getString(R.string.channel_name_notification),
                    NotificationManager.IMPORTANCE_DEFAULT)
            activity.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(activity, activity.getString(R.string.channel_id_notification))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(activity.getString(R.string.app_name))
                .setContentText("Repackaging $packageName")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(activity).notify(packageName.hashCode(), notification)

        val wrapper = AbstractTaskWrapper(app.selectedPrivacyLevels, app.selectedPrivacyLevelsLock, arrayOf(File(apkPath)), app.isDebugEnabled)
        val task = RepackageTask(activity, packageName, splitApkPaths.map { File(it) }, removeCheckLicensePermission)
        if (onFinished != null) {
            task.setOnFinishedListener(onFinished)
        }

        // The list lets the user navigate away while this runs in the background
        // (unlike the modal modification screen); dismiss the progress dialog
        // right away instead of leaking its window once the Activity is destroyed.
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    task.dismissDialogSafely()
                }
            })
        }

        task.start(wrapper)

        storeStatus(packageName, ApplicationStatus(packageName = packageName, isInRepackaging = true))
    }
}
