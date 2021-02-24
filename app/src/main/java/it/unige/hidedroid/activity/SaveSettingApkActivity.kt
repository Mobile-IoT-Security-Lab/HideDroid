package it.unige.hidedroid.activity

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.transition.Explode
import android.view.Window
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import es.dmoral.toasty.Toasty
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.log.LoggerHideDroid
import com.dave.realmdatahelper.hidedroid.ApplicationStatus
import com.dave.realmdatahelper.debug.Error
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import com.dave.realmdatahelper.utils.Utils
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.task.AbstractTaskWrapper
import it.unige.hidedroid.task.RepackageTask
import it.unige.hidedroid.utils.Utilities
import kotlinx.android.synthetic.main.activity_settings_app.buttonSaveSetting
import kotlinx.android.synthetic.main.activity_settings_app.iconInstallApp
import kotlinx.android.synthetic.main.activity_settings_app.idPackageNameInstallApp
import kotlinx.android.synthetic.main.activity_settings_app.labelPath
import kotlinx.android.synthetic.main.activity_settings_app.pathApk
import kotlinx.android.synthetic.main.activity_settings_app.privacySeekBar
import kotlinx.android.synthetic.main.activity_settings_app.textViewPrivacyLevel
import kotlinx.android.synthetic.main.activity_settings_app_v2.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SaveSettingApkActivity : AppCompatActivity(),SeekBar.OnSeekBarChangeListener {

    private var newApkPath: String? = null
    private var apkPath: String? = null
    private var appPackageNameToModify: String? = null
    private val notifyID = 0
    private var apkName: String? = null
    private var privacyLevel: Int = DEFAULT_VALUE_PRIVACY_LEVEL
    private var appPrivacySetting: PackageNamePrivacyLevel? = null
    private var systemCaInstalled: Boolean = false
    private lateinit var isDebugEnabled: AtomicBoolean

    companion object {
        private val TAG = SaveSettingApkActivity::class.java.name
        val MSG_SAVEAPKSERVICE = "MSG_SAVEAPKSERVICE"
        val DEFAULT_VALUE_PRIVACY_LEVEL = 1
        val APP_REMOVING = 2
        val APP_INSTALLING = 3
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        with(window) {
            requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            // set set the transition to be shown when the user enters this activity
            enterTransition = Explode()
            // set the transition to be shown when the user leaves this activity
            exitTransition = Explode()
        }
        // single app
        setContentView(R.layout.activity_settings_app_v2)

        // list of apps
        // setContentView(R.layout.activity_select_apps)

        // get parameters
        val intent = intent
        val bundle = intent.extras
        var isInstalledIntent: String? = null

        // get info from intent
        if (bundle != null) {
            val apkAndMsgs = bundle.getStringArray(MSG_SAVEAPKSERVICE)
                    ?: throw AssertionError("Cannot read output messages")
            if (apkAndMsgs.isNotEmpty()) {
                apkPath = apkAndMsgs[0] // path apk
                appPackageNameToModify = apkAndMsgs[1] // package name
                isInstalledIntent = apkAndMsgs[2] // if is already installed or not
            }
        }

        // app already installed
        if(isInstalledIntent!!.toBoolean()) {
            iconInstallApp.setImageDrawable(packageManager.getApplicationIcon(appPackageNameToModify))
            idPackageNameInstallApp.text = appPackageNameToModify
            apkName = getApkFileName(apkPath!!, false)
            newApkPath = File(File(apkPath).parent, apkName + "_signed.apk").absolutePath

        } else {
            iconInstallApp.setImageDrawable(packageManager.getPackageArchiveInfo(apkPath, 0)
                    .applicationInfo.loadIcon(packageManager))
            idPackageNameInstallApp.text = appPackageNameToModify
            apkName = getApkFileName(apkPath!!, true)
            newApkPath = apkPath!!
        }

        // modify text if app already repacked or not
        changeButtonSettings()
        // modify seek bar
        setPrivacyLevelSeekBar()

        // add listener
        privacySeekBar.setOnSeekBarChangeListener(this)
        buttonSaveSetting.setOnClickListener {
            clickSaveSettingsButton()
        }

        isDebugEnabled = (this.application as HideDroidApplication).isDebugEnabled
    }

    override fun onResume() {
        appPrivacySetting = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(appPackageNameToModify!!)
        isDebugEnabled = (this.application as HideDroidApplication).isDebugEnabled

        setPrivacyLevelSeekBar()
        changeButtonSettings()

        super.onResume()
    }

    override fun onRestart() {
        changeButtonSettings()
        isDebugEnabled = (this.application as HideDroidApplication).isDebugEnabled
        super.onRestart()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == APP_REMOVING) {
            val appStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(appPackageNameToModify!!)

            if (resultCode == Activity.RESULT_OK && appStatus != null) {
                LoggerHideDroid.d(TAG, "App Removed successfully")
                appStatus.update(ApplicationStatus(isRepackaged = true, isInRemoving = false))
                appStatus.storeStateApp()
                Toasty.success(this, "App Removed Successfully").show()

            } else if (resultCode == Activity.RESULT_FIRST_USER && appStatus != null) {
                if (isDebugEnabled.get()) {
                    Error(appPackageNameToModify!!, "", "", "", "App not removed. Status: $appStatus. ResultCode: $resultCode").insertOrUpdateError((this.application as HideDroidApplication).realmConfigLog)
                    Utils().postToTelegramServer((this.application as HideDroidApplication).androidId, (System.currentTimeMillis() / 1000).toString(), "App not removed. Status: $appStatus. ResultCode: $resultCode --- app: $appPackageNameToModify!!", "appRemoving", "error")
                }
                LoggerHideDroid.d(TAG, "App Not Removed")
                Toasty.error(this, "App Not Removed").show()
                appStatus.update(ApplicationStatus(isRepackaged = true, isInRemoving = false))
                appStatus.storeStateApp()

            } else {
                if (isDebugEnabled.get()) {
                    Error(appPackageNameToModify!!, "", "", "", "Unknown problem during uninstall. Status: $appStatus. ResultCode: $resultCode").insertOrUpdateError((this.application as HideDroidApplication).realmConfigLog)
                    Utils().postToTelegramServer((this.application as HideDroidApplication).androidId, (System.currentTimeMillis() / 1000).toString(), "Unknown problem during uninstall. Status: $appStatus. ResultCode: $resultCode --- app: $appPackageNameToModify!!", "appRemoving", "debug")
                }
            }

        } else if (requestCode == APP_INSTALLING ){
            val appStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(appPackageNameToModify!!)
            if (resultCode == Activity.RESULT_OK && appStatus != null) {
                LoggerHideDroid.d(TAG, "App Modified Installed Succesfully")

                appStatus.update(ApplicationStatus(installed = true, isRepackaged = true))
                appStatus.storeStateApp()
                val packageNamePrivacyLevel = PackageNamePrivacyLevel(
                        packageName = appPackageNameToModify!!,
                        isInstalled = true,
                        privacyLevel = privacyLevel)
                packageNamePrivacyLevel.storePrivacySettings()
                (this.application as HideDroidApplication).selectedPrivacyLevelsLock.lock()
                try {
                    if ((this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] != null) {
                        (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!]!!.set(privacyLevel)
                    } else {
                        (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] = AtomicInteger(privacyLevel)
                    }
                } finally {
                    (this.application as HideDroidApplication).selectedPrivacyLevelsLock.unlock()
                }
                Toasty.success(this, "Privacy Settings Stored and App Installed").show()

                AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                        .setMessage(R.string.dialog_message)
                        ?.setTitle(R.string.dialog_title)
                        ?.setPositiveButton(R.string.dialog_button) { _, _ ->}
                        ?.create()
                        ?.show()

            } else if (resultCode == Activity.RESULT_FIRST_USER && appStatus != null) {
                if (isDebugEnabled.get()) {
                    Error(appPackageNameToModify!!, "", "", "", "App not installed. Status: $appStatus. ResultCode: $resultCode").insertOrUpdateError((this.application as HideDroidApplication).realmConfigLog)
                    Utils().postToTelegramServer((this.application as HideDroidApplication).androidId, (System.currentTimeMillis() / 1000).toString(), "App not installed. Status: $appStatus. ResultCode: $resultCode --- app: $appPackageNameToModify!!", "appInstalling", "error")
                }
                LoggerHideDroid.d(TAG, "App Not Installed")
                appStatus.update(ApplicationStatus(installed = false, isRepackaged = true))
                appStatus.storeStateApp()

                val packageNamePrivacyLevel = PackageNamePrivacyLevel(
                        packageName = appPackageNameToModify!!,
                        isInstalled = false,
                        privacyLevel = privacyLevel)
                packageNamePrivacyLevel.storePrivacySettings()
                (this.application as HideDroidApplication).selectedPrivacyLevelsLock.lock()
                try {
                    if ((this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] != null) {
                        (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!]!!.set(privacyLevel)
                    } else {
                        (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] = AtomicInteger(privacyLevel)
                    }
                } finally {
                    (this.application as HideDroidApplication).selectedPrivacyLevelsLock.unlock()
                }
                Toasty.error(this, "App Not Installed").show()

            } else {
                if (isDebugEnabled.get()) {
                    Error(appPackageNameToModify!!, "", "", "", "Unknown problem during app install. Status: $appStatus. ResultCode: $resultCode").insertOrUpdateError((this.application as HideDroidApplication).realmConfigLog)
                    Utils().postToTelegramServer((this.application as HideDroidApplication).androidId, (System.currentTimeMillis() / 1000).toString(), "Unknown problem during app install. Status: $appStatus. ResultCode: $resultCode --- app: $appPackageNameToModify!!", "appInstalling", "debug")
                }
            }
        }
    }

    fun changeButtonSettings() {
        val appPrivacySetting = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(appPackageNameToModify!!)
        systemCaInstalled = this.getPreferences(Context.MODE_PRIVATE).getBoolean(getString(R.string.key_root_certificate_preference), false)
        when {
            systemCaInstalled -> {
                buttonSaveSetting.text = getString(R.string.click_to_save_settings)
                labelPath.text = ""
                pathApk.text = ""
                informationRepackaging.text = getString(R.string.informative_apply_privacy_level)
            }

            appPrivacySetting != null && appPrivacySetting.isInstalled -> {
                if (Utilities.isVersionGreaterThanNougat() && buttonSaveSetting.text == getString(R.string.click_to_repacking)) {
                    AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                            .setMessage(R.string.dialog_message)
                            ?.setTitle(R.string.dialog_title)
                            ?.setPositiveButton(R.string.dialog_button) { _, _ ->}
                            ?.create()
                            ?.show()
                    Toasty.success(this, "The application is already suitable to be tracked").show()
                }
                buttonSaveSetting.text = getString(R.string.click_to_save_settings)
                labelPath.text = ""
                pathApk.text = ""
                informationRepackaging.text = getString(R.string.informative_apply_privacy_level)
            }
            isAppAlreadyRepacked() -> {
                buttonSaveSetting.text = getString(R.string.click_to_install_app)
                labelPath.text = ""
                pathApk.text = ""
                informationRepackaging.text = ""

            }
            Utilities.isVersionGreaterThanNougat() -> {
                buttonSaveSetting.text = getString(R.string.click_to_repacking)
                labelPath.text = ""
                pathApk.text = ""
                informationRepackaging.text = getString(R.string.informative_app_modification)
            }
            else -> {
                buttonSaveSetting.text = getString(R.string.click_to_save_settings)
                labelPath.text = ""
                pathApk.text = ""
                informationRepackaging.text = getString(R.string.informative_apply_privacy_level)
            }
        }
    }

    private fun clickSaveSettingsButton() {
        systemCaInstalled = this.getPreferences(Context.MODE_PRIVATE).getBoolean(getString(R.string.key_root_certificate_preference), false)
        appPrivacySetting = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(appPackageNameToModify!!)
        if (appPrivacySetting == null || !appPrivacySetting!!.isInstalled) {
            if (Utilities.isVersionGreaterThanNougat()) {
                when {
                    systemCaInstalled -> {
                        val packageNamePrivacyLevel = PackageNamePrivacyLevel(
                                packageName = appPackageNameToModify!!,
                                isInstalled = true,
                                privacyLevel = privacyLevel
                        )
                        packageNamePrivacyLevel.storePrivacySettings()
                        (this.application as HideDroidApplication).selectedPrivacyLevelsLock.lock()
                        try {
                            if ((this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] != null) {
                                (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!]!!.set(privacyLevel)
                            } else {
                                (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] = AtomicInteger(privacyLevel)
                            }
                        } finally {
                            (this.application as HideDroidApplication).selectedPrivacyLevelsLock.unlock()
                        }
                        Toasty.success(this, "Privacy Settings Updated").show()
                    }
                    isAppAlreadyRepacked() -> {
                        LoggerHideDroid.d(TAG, "Beginning app installing")
                        installApk()
                    }
                    else -> {
                        LoggerHideDroid.d(TAG, "Beginning app installing")
                        startRepacking()
                    }
                }

            } else {
                // no need repackaging
                val packageNamePrivacyLevel = PackageNamePrivacyLevel(
                        packageName = appPackageNameToModify!!,
                        isInstalled = true,
                        privacyLevel = privacyLevel
                )
                packageNamePrivacyLevel.storePrivacySettings()
                (this.application as HideDroidApplication).selectedPrivacyLevelsLock.lock()
                try {
                    if ((this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] != null) {
                        (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!]!!.set(privacyLevel)
                    } else {
                        (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] = AtomicInteger(privacyLevel)
                    }
                } finally {
                    (this.application as HideDroidApplication).selectedPrivacyLevelsLock.unlock()
                }
                AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                        .setMessage(R.string.dialog_message)
                        ?.setTitle(R.string.dialog_title)
                        ?.setPositiveButton(R.string.dialog_button) { _, _ ->}
                        ?.create()
                        ?.show()
                Toasty.success(this, "Privacy Settings Updated").show()

            }
        } else {
            LoggerHideDroid.d(TAG, "$privacyLevel")
            val packageNamePrivacyLevel = PackageNamePrivacyLevel(
                    packageName = appPackageNameToModify!!,
                    isInstalled = true,
                    privacyLevel = privacyLevel
            )
            packageNamePrivacyLevel.storePrivacySettings()
            (this.application as HideDroidApplication).selectedPrivacyLevelsLock.lock()
            try {
                if ((this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] != null) {
                    (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!]!!.set(privacyLevel)
                } else {
                    (this.application as HideDroidApplication).selectedPrivacyLevels[appPackageNameToModify!!] = AtomicInteger(privacyLevel)
                }
            } finally {
                (this.application as HideDroidApplication).selectedPrivacyLevelsLock.unlock()
            }
            Toasty.success(this, "Privacy Settings Updated").show()
        }
    }

    private fun isAppAlreadyRepacked(): Boolean {
        val file = File(newApkPath)
        return file.exists()
    }

    private fun startRepacking() {

        val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
        alertDialogBuilder
                .setTitle(getString(R.string.android_versione_7))
                .setMessage(getString(R.string.ask_app_modification))
                .setPositiveButton("Yes") { dialog, _ ->
                    performRepacking(apkPath!!)
                }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }

        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    private fun performRepacking(apkPath: String) {
        // create notification channel if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = getString(R.string.channel_name_notification)
            val description = "test description"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(getString(R.string.channel_id_notification), name, importance)
            channel.description = description
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // create notification
        val startNotification = NotificationCompat.Builder(this, "test id")
                .setSmallIcon(R.mipmap.ic_launcher_circle)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Repackaging $appPackageNameToModify")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

        val notificationManager = NotificationManagerCompat.from(this)
        notificationManager.notify(notifyID, startNotification.build())

        val files = Array(1) { File(apkPath) }
        val wrapper = AbstractTaskWrapper((this.application as HideDroidApplication).selectedPrivacyLevels, (this.application as HideDroidApplication).selectedPrivacyLevelsLock,
                files, isDebugEnabled, (this.application as HideDroidApplication).realmConfigLog, (this.application as HideDroidApplication).androidId)
        RepackageTask(this, apkName).execute(wrapper)

        var appStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(appPackageNameToModify!!)
        if (appStatus!= null) {
            appStatus.update(com.dave.realmdatahelper.hidedroid.ApplicationStatus(packageName = appPackageNameToModify!!, isInRepackaging = true))
        } else {
            appStatus = com.dave.realmdatahelper.hidedroid.ApplicationStatus(packageName = appPackageNameToModify!!, isInRepackaging = true)
            appStatus.storeStateApp()
        }
            /*
        UtilitiesStoreDataOnDb.storeStateApp(packageName = appPackageNameToModify!!, isInRepackaging = true)
        */
    }

    private fun getApkFileName(path: String, isSigned: Boolean): String? {
        return if (!isSigned) {
            if (path.substring(path.length - 4) == ".apk") {
                path.substring(path.lastIndexOf("/") + 1, path.length - 4)
            } else null
        } else {
            if (path.substring(path.length - 11) == "_signed.apk") {
                path.substring(path.lastIndexOf("/") + 1, path.length - 11)
            } else null
        }
    }

    private fun installApk() {

        // install application
        val newApkFile = File(newApkPath)
        // Check if the app is already installed.
        val pi = packageManager.getPackageArchiveInfo(newApkPath, 0)
        if (pi != null) {
            val installed: Boolean = try {
                packageManager.getPackageInfo(pi.packageName, PackageManager.GET_ACTIVITIES)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }

            if (installed) {
                val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                alertDialogBuilder
                        .setTitle(getString(R.string.app_already_installed))
                        .setMessage(getString(R.string.ask_removing_app))
                        .setPositiveButton("Yes") { dialog, id ->
                            // new
                            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                                    Uri.fromParts("package", pi.packageName, null))
                            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
                            startActivityForResult(intent, APP_REMOVING)

                            // OLD
                            // val intent = Intent(Intent.ACTION_DELETE,
                            // Uri.fromParts("package", pi.packageName, null))
                            //startActivity(intent)
                            var appStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(appPackageNameToModify!!)
                            if (appStatus!=null) {
                                appStatus!!.update(com.dave.realmdatahelper.hidedroid.ApplicationStatus(packageName = appPackageNameToModify!!, isInRemoving = true, isInRepackaging = true))
                            } else {
                                appStatus = com.dave.realmdatahelper.hidedroid.ApplicationStatus(packageName = appPackageNameToModify!!, isInRemoving = true, isInRepackaging = true)
                                appStatus.storeStateApp()
                            }
                            /*
                            UtilitiesStoreDataOnDb.storeStateApp(packageName = appPackageNameToModify!!,
                                    isInRemoving = true, isRepackaged = true)
                            */


                        }
                        .setNegativeButton("No") { dialog, id -> dialog.cancel() }
                val alertDialog = alertDialogBuilder.create()
                alertDialog.show()
                return
            }
        }

        val uri: Uri
        uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this,
                    applicationContext.packageName + ".fileprovider", newApkFile)
        } else {
            Uri.fromFile(newApkFile)
        }

        /* OLD
        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        startActivity(intent)
        */

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        startActivityForResult(intent, APP_INSTALLING)

        // store data
        var applicationStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(appPackageNameToModify!!)
        if (applicationStatus != null)
            applicationStatus.update(com.dave.realmdatahelper.hidedroid.ApplicationStatus(isInInstalling = true))
        else
            applicationStatus = com.dave.realmdatahelper.hidedroid.ApplicationStatus(appPackageNameToModify!!, isInInstalling = true)
            applicationStatus.storeStateApp()
        //UtilitiesStoreDataOnDb.storeStateApp(packageName = appPackageNameToModify!!,
        //    isInInstalling = true)

    }

    override fun onProgressChanged(p0: SeekBar?, p1: Int, p2: Boolean) {

    }

    override fun onStartTrackingTouch(p0: SeekBar?) {

    }

    override fun onStopTrackingTouch(p0: SeekBar?) {
        // update color seekbar
        privacyLevel = privacySeekBar!!.progress

        updateSeekBar(privacyLevel)

    }

    private fun updateSeekBar(privacyLevel: Int) {
        when(privacyLevel) {
            0 -> {
                textViewPrivacyLevel.text = "NONE"
                privacySeekBar.progressDrawable = getDrawable(R.drawable.seek_bar_none)
                privacySeekBar.thumb = getDrawable(R.drawable.seek_thumb_none)
            }
            1 -> {
                textViewPrivacyLevel.text = "LOW"
                privacySeekBar.progressDrawable = getDrawable(R.drawable.seek_bar_low)
                privacySeekBar.thumb = getDrawable(R.drawable.seek_thumb_low)

            }
            2 -> {
                textViewPrivacyLevel.text = "MEDIUM"
                privacySeekBar.progressDrawable = getDrawable(R.drawable.seek_bar_medium)
                privacySeekBar.thumb = getDrawable(R.drawable.seek_thumb_medium)


            }
            3 -> {
                textViewPrivacyLevel.text = "HIGH"
                privacySeekBar.progressDrawable = getDrawable(R.drawable.seek_bar_high)
                privacySeekBar.thumb = getDrawable(R.drawable.seek_thumb_high)
            }
        }
    }

    private fun setPrivacyLevelSeekBar() {
        if (appPrivacySetting!=null && appPrivacySetting!!.isInstalled) {
            privacySeekBar!!.progress = appPrivacySetting!!.privacyLevel
            privacyLevel = appPrivacySetting!!.privacyLevel
            updateSeekBar(privacyLevel)
        }
    }

}