package it.unige.hidedroid.utils

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.dave.realmdatahelper.hidedroid.ApplicationStatus
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import es.dmoral.toasty.Toasty
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Installs a freshly-repackaged (re-signed) APK, uninstalling any currently
 * installed build of the same app first - required since the resigned APK's
 * signature never matches the original's. Shared between the per-app
 * modification screen and the app list's quick "Repack" action so both
 * chain uninstall -> install as a single flow instead of two separate taps.
 *
 * The hosting Activity must register [uninstallLauncher]/[installLauncher]
 * in onCreate() (ActivityResultLauncher can't be registered later) and
 * forward their results to [onUninstallResult]/[onInstallResult].
 */
object InstallActions {

    private data class PendingInstall(val packageName: String, val signedApkPath: String, val privacyLevel: Int)

    // Only one uninstall/install system dialog can be on screen at a time,
    // so a single in-flight slot is enough - no need for a queue/map here.
    private var pending: PendingInstall? = null

    fun installSignedApk(
            activity: Activity,
            packageName: String,
            signedApkPath: String,
            privacyLevel: Int,
            uninstallLauncher: ActivityResultLauncher<Intent>,
            installLauncher: ActivityResultLauncher<Intent>
    ) {
        if (!isCurrentlyInstalled(activity, packageName)) {
            launchInstall(activity, packageName, signedApkPath, installLauncher)
            return
        }

        AlertDialog.Builder(activity, R.style.CustomAlertDialogRounded)
                .setTitle(activity.getString(R.string.app_already_installed))
                .setMessage(activity.getString(R.string.ask_removing_app))
                .setPositiveButton("Yes") { _, _ ->
                    pending = PendingInstall(packageName, signedApkPath, privacyLevel)
                    val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", packageName, null))
                    intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    try {
                        uninstallLauncher.launch(intent)
                        storeStatus(packageName, ApplicationStatus(packageName = packageName, isInRemoving = true, isInRepackaging = true))
                    } catch (e: SecurityException) {
                        pending = null
                        Toasty.error(activity, "Cannot start uninstaller: ${e.message}").show()
                    }
                }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }
                .show()
    }

    /** Forward the hosting Activity's uninstallLauncher result here. */
    fun onUninstallResult(activity: Activity, resultCode: Int, installLauncher: ActivityResultLauncher<Intent>) {
        val request = pending
        pending = null
        if (request == null) return

        storeStatus(request.packageName, ApplicationStatus(packageName = request.packageName, isRepackaged = true, isInRemoving = false))
        if (resultCode == Activity.RESULT_OK) {
            Toasty.success(activity, "Old app removed, installing the repackaged version...").show()
            launchInstall(activity, request.packageName, request.signedApkPath, installLauncher)
        } else {
            Toasty.error(activity, "App not removed; cannot install the repackaged version").show()
        }
    }

    /** Forward the hosting Activity's installLauncher result here. */
    fun onInstallResult(activity: Activity, resultCode: Int, packageName: String, privacyLevel: Int) {
        if (resultCode == Activity.RESULT_OK) {
            storeStatus(packageName, ApplicationStatus(packageName = packageName, installed = true, isRepackaged = true))
            rememberSelectedPrivacyLevel(activity, packageName, privacyLevel)
            PackageNamePrivacyLevel(packageName = packageName, isInstalled = true, privacyLevel = privacyLevel)
                    .storePrivacySettings()
            Toasty.success(activity, "Privacy Settings Stored and App Installed").show()
        } else if (resultCode == Activity.RESULT_FIRST_USER) {
            storeStatus(packageName, ApplicationStatus(packageName = packageName, installed = false, isRepackaged = true))
            Toasty.error(activity, "App Not Installed").show()
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

    private fun rememberSelectedPrivacyLevel(activity: Activity, packageName: String, privacyLevel: Int) {
        val app = activity.application as HideDroidApplication
        app.selectedPrivacyLevelsLock.lock()
        try {
            val existing = app.selectedPrivacyLevels[packageName]
            if (existing != null) existing.set(privacyLevel) else app.selectedPrivacyLevels[packageName] = AtomicInteger(privacyLevel)
        } finally {
            app.selectedPrivacyLevelsLock.unlock()
        }
    }

    private fun isCurrentlyInstalled(activity: Activity, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun launchInstall(activity: Activity, packageName: String, signedApkPath: String, installLauncher: ActivityResultLauncher<Intent>) {
        val apkFile = File(signedApkPath)
        val uri: Uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(activity, activity.applicationContext.packageName + ".fileprovider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }
        } catch (e: IllegalArgumentException) {
            Toasty.error(activity, "APK path not accessible: ${e.message}").show()
            return
        }

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        try {
            installLauncher.launch(intent)
        } catch (e: SecurityException) {
            Toasty.error(activity, "Cannot start installer: ${e.message}").show()
            return
        }

        storeStatus(packageName, ApplicationStatus(packageName = packageName, isInInstalling = true))
    }
}
