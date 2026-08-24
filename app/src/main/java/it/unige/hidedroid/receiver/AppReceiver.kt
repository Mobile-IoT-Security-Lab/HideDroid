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
            LoggerHideDroid.d(TAG, "Receiver App Removed")
            val packageName = intent.data.toString().split("package:")[1]
            LoggerHideDroid.d(TAG, "App removed: $packageName")

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val applicationStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(packageName)
                    val packageNamePrivacyLevel = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(packageName)
                    applicationStatus?.deleteThisFromRealm()
                    packageNamePrivacyLevel?.deleteThisFromRealm()

                    val appContext = context as HideDroidApplication
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