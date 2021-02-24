package it.unige.hidedroid.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import it.unige.hidedroid.log.LoggerHideDroid
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb

class AppReceiver:BroadcastReceiver() {
    // TODO update db if removed app
    companion object{
        val TAG: String = AppReceiver::class.java.name
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null && (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED || intent.action == Intent.ACTION_PACKAGE_REMOVED)) {
            LoggerHideDroid.d(TAG, "Receiver App Removed")
            val packageName = intent.data.toString().split("package:")[1]
            LoggerHideDroid.d(TAG, "App removed: $packageName")
            val applicationStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(packageName)
            val packageNamePrivacyLevel = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(packageName)
            applicationStatus?.deleteThisFromRealm()
            packageNamePrivacyLevel?.deleteThisFromRealm()

            val selectedPrivacyLevels = (context as HideDroidApplication).selectedPrivacyLevels
            context.selectedPrivacyLevelsLock.lock()
            try {
                if (selectedPrivacyLevels.containsKey(packageName)) {
                    selectedPrivacyLevels.remove(packageName)
                }
            } finally {
                context.selectedPrivacyLevelsLock.unlock()
            }
        }
    }
}