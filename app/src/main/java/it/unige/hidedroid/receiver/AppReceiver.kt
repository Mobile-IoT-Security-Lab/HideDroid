package it.unige.hidedroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AppReceiver : BroadcastReceiver() {
    // TODO update db if removed app
    companion object {
        val TAG: String = AppReceiver::class.java.name
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null && (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED || intent.action == Intent.ACTION_PACKAGE_REMOVED)) {
            // ACTION_PACKAGE_REMOVED also fires for a normal app update (old APK
            // removed, EXTRA_REPLACING=true, followed by ACTION_PACKAGE_ADDED for
            // the new one) - the app isn't actually gone, so don't untrack it.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return
            }

            LoggerHideDroid.d(TAG, "Receiver App Removed")
            val packageName = intent.data.toString().split("package:")[1]
            LoggerHideDroid.d(TAG, "App removed: $packageName")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val applicationStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(packageName)

                    // HideDroid's own repack flow uninstalls the old signature before
                    // installing the resigned build (isInRemoving is set right before
                    // that uninstall - see InstallActions.installSignedApk); this
                    // broadcast for that self-triggered removal must not race with
                    // InstallActions' own state writes and wipe tracking for an app
                    // that's actually mid-reinstall, not actually being dropped.
                    if (applicationStatus?.isInRemoving == true) {
                        return@launch
                    }

                    val packageNamePrivacyLevel = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(packageName)
                    applicationStatus?.deleteThisFromRealm()
                    packageNamePrivacyLevel?.deleteThisFromRealm()

                    val appContext = context?.applicationContext as HideDroidApplication
                    appContext.selectedPrivacyLevelsLock.lock()
                    try {
                        appContext.selectedPrivacyLevels.remove(packageName)
                    } finally {
                        appContext.selectedPrivacyLevelsLock.unlock()
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}