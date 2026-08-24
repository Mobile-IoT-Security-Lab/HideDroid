package it.unige.hidedroid.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.transition.Explode
import android.util.Log
import android.view.Window
import android.widget.SeekBar
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dave.realmdatahelper.hidedroid.ApplicationStatus
import com.google.android.material.button.MaterialButton
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel
import es.dmoral.toasty.Toasty
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.utils.FolderPicker
import it.unige.hidedroid.utils.HideDroidStorage
import it.unige.hidedroid.utils.InstallActions
import it.unige.hidedroid.utils.TrackingActions
import it.unige.hidedroid.utils.UserCertTrustChecker
import it.unige.hidedroid.utils.Utilities
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SaveSettingApkActivity : AppCompatActivity(), SeekBar.OnSeekBarChangeListener {

    private var newApkPath: String? = null
    private var apkPath: String? = null
    private var splitApkPaths: List<String> = emptyList()
    private var appPackageNameToModify: String? = null
    private var privacyLevel: Int = DEFAULT_VALUE_PRIVACY_LEVEL
    private var appPrivacySetting: PackageNamePrivacyLevel? = null
    private var currentlyTrackedWithoutRepack: Boolean = false
    private var systemCaInstalled: Boolean = false
    private lateinit var isDebugEnabled: AtomicBoolean
    private lateinit var buttonSaveSetting: android.widget.Button
    private lateinit var iconInstallApp: android.widget.ImageView
    private lateinit var idPackageNameInstallApp: android.widget.TextView
    private lateinit var labelPath: android.widget.TextView
    private lateinit var pathApk: android.widget.TextView
    private lateinit var privacySeekBar: android.widget.SeekBar
    private lateinit var textViewPrivacyLevel: android.widget.TextView
    private lateinit var informationRepackaging: android.widget.TextView
    private lateinit var textViewTrackingStatus: android.widget.TextView
    private lateinit var buttonRepackAppView: MaterialButton
    private lateinit var buttonNoRepackAppView: MaterialButton

    companion object {
        private val TAG = SaveSettingApkActivity::class.java.name
        val MSG_SAVEAPKSERVICE = "MSG_SAVEAPKSERVICE"
        val DEFAULT_VALUE_PRIVACY_LEVEL = 1
    }

    private lateinit var uninstallLauncher: ActivityResultLauncher<Intent>
    private lateinit var installLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleUninstallResult(result.resultCode)
        }

        installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleInstallResult(result.resultCode)
        }

        with(window) {
            requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            // set set the transition to be shown when the user enters this activity
            enterTransition = Explode()
            // set the transition to be shown when the user leaves this activity
            exitTransition = Explode()
        }
        // single app
        setContentView(R.layout.activity_settings_app_v2)
        buttonSaveSetting = findViewById(R.id.buttonSaveSetting)
        iconInstallApp = findViewById(R.id.iconInstallApp)
        idPackageNameInstallApp = findViewById(R.id.idPackageNameInstallApp)
        labelPath = findViewById(R.id.labelPath)
        pathApk = findViewById(R.id.pathApk)
        privacySeekBar = findViewById(R.id.privacySeekBar)
        textViewPrivacyLevel = findViewById(R.id.textViewPrivacyLevel)
        informationRepackaging = findViewById(R.id.informationRepackaging)
        textViewTrackingStatus = findViewById(R.id.textViewTrackingStatus)
        buttonRepackAppView = findViewById(R.id.buttonRepackAppView)
        buttonNoRepackAppView = findViewById(R.id.buttonNoRepackAppView)

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
                splitApkPaths = apkAndMsgs.drop(3) // split APKs (config/density/ABI/language), if any
            }
        }

        // app already installed
        if (isInstalledIntent!!.toBoolean()) {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                        appPackageNameToModify!!,
                        PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(appPackageNameToModify!!, 0)
            }
            iconInstallApp.setImageDrawable(appInfo.loadIcon(packageManager))
            idPackageNameInstallApp.text = appPackageNameToModify

        } else {
            @Suppress("DEPRECATION")
            val archiveInfo = packageManager.getPackageArchiveInfo(apkPath!!, 0)
            iconInstallApp.setImageDrawable(archiveInfo?.applicationInfo?.loadIcon(packageManager))
            idPackageNameInstallApp.text = appPackageNameToModify
        }
        newApkPath = HideDroidStorage.getRepackedApkFile(appPackageNameToModify!!).absolutePath

        isDebugEnabled = (this.application as HideDroidApplication).isDebugEnabled

        // modify text if app already repacked or not
        changeButtonSettings()
        // modify seek bar
        setPrivacyLevelSeekBar()

        // add listener
        privacySeekBar.setOnSeekBarChangeListener(this)
        buttonSaveSetting.setOnClickListener {
            clickSaveSettingsButton()
        }
        buttonRepackAppView.setOnClickListener {
            setUpOutputFolderThenRepack()
        }
        buttonNoRepackAppView.setOnClickListener {
            if (currentlyTrackedWithoutRepack) {
                resetTracking()
            } else {
                interceptWithoutModification()
            }
        }
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

    private fun handleUninstallResult(resultCode: Int) {
        InstallActions.onUninstallResult(this, resultCode, installLauncher)
    }

    private fun handleInstallResult(resultCode: Int) {
        InstallActions.onInstallResult(this, resultCode, appPackageNameToModify!!, privacyLevel)
        if (resultCode == Activity.RESULT_OK) {
            AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                    .setMessage(R.string.dialog_message)
                    ?.setTitle(R.string.dialog_title)
                    ?.setPositiveButton(R.string.dialog_button) { _, _ -> }
                    ?.create()
                    ?.show()
        }
    }

    fun changeButtonSettings() {
        val appPrivacySetting = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(appPackageNameToModify!!)
        systemCaInstalled = this.getPreferences(Context.MODE_PRIVATE).getBoolean(getString(R.string.key_root_certificate_preference), false)
        updateTrackingStatus(appPrivacySetting)
        when {
            systemCaInstalled -> {
                buttonSaveSetting.text = getString(R.string.click_to_save_settings)
                labelPath.text = ""
                pathApk.text = ""
                informationRepackaging.text = getString(R.string.informative_apply_privacy_level)
            }

            appAlreadyTrustsUserCerts() -> {
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
                            ?.setPositiveButton(R.string.dialog_button) { _, _ -> }
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

    /** Refreshes the Repack/No repack/Tracked status label and which of the two mode buttons shows as active (green). */
    private fun updateTrackingStatus(appPrivacySetting: PackageNamePrivacyLevel?) {
        val isRepackaged = if (appPrivacySetting != null) {
            UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(appPackageNameToModify!!)?.isRepackaged
        } else {
            null
        }
        textViewTrackingStatus.text = when {
            appPrivacySetting == null -> getString(R.string.status_not_tracked)
            isRepackaged == true -> getString(R.string.status_tracked_repacked)
            isRepackaged == false -> getString(R.string.status_tracked_no_repack)
            else -> getString(R.string.status_not_tracked)
        }
        // Both stay tappable: tapping the already-active No repack button resets tracking
        // instead of no-op'ing (see buttonNoRepackAppView's click listener).
        buttonRepackAppView.isChecked = isRepackaged == true
        buttonNoRepackAppView.isChecked = isRepackaged == false
        currentlyTrackedWithoutRepack = isRepackaged == false
    }

    /** Tapping "No repack" while it's already the active mode drops tracking entirely instead of re-applying it. */
    private fun resetTracking() {
        TrackingActions.resetTracking(this, appPackageNameToModify!!)
        changeButtonSettings()
        Toasty.info(this, getString(R.string.reset_tracking_result)).show()
    }

    private fun clickSaveSettingsButton() {
        systemCaInstalled = this.getPreferences(Context.MODE_PRIVATE).getBoolean(getString(R.string.key_root_certificate_preference), false)
        appPrivacySetting = UtilitiesStoreDataOnRealmDb.getPrivacySettingAppFromPackageName(appPackageNameToModify!!)
        if (appPrivacySetting == null || !appPrivacySetting!!.isInstalled) {
            if (Utilities.isVersionGreaterThanNougat()) {
                when {
                    appAlreadyTrustsUserCerts() -> {
                        storePrivacySettingsForTrustedApp()
                    }
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
                        ?.setPositiveButton(R.string.dialog_button) { _, _ -> }
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

    private fun appAlreadyTrustsUserCerts(): Boolean {
        val path = apkPath ?: return false
        val file = File(path)
        if (!file.exists()) return false
        return try {
            UserCertTrustChecker.apkTrustsUserCertificates(this, file)
        } catch (e: Exception) {
            if (isDebugEnabled.get()) {
                Log.e(TAG, "Failed to check user-cert trust for ${file.name}", e)
            }
            false
        }
    }

    private fun storePrivacySettingsForTrustedApp() {
        val packageName = appPackageNameToModify!!
        val packageNamePrivacyLevel = PackageNamePrivacyLevel(
                packageName = packageName,
                isInstalled = true,
                privacyLevel = privacyLevel
        )
        packageNamePrivacyLevel.storePrivacySettings()
        (this.application as HideDroidApplication).selectedPrivacyLevelsLock.lock()
        try {
            if ((this.application as HideDroidApplication).selectedPrivacyLevels[packageName] != null) {
                (this.application as HideDroidApplication).selectedPrivacyLevels[packageName]!!.set(privacyLevel)
            } else {
                (this.application as HideDroidApplication).selectedPrivacyLevels[packageName] = AtomicInteger(privacyLevel)
            }
        } finally {
            (this.application as HideDroidApplication).selectedPrivacyLevelsLock.unlock()
        }

        var appStatus = UtilitiesStoreDataOnRealmDb.getStatusAppFromPackageName(packageName)
        val status = ApplicationStatus(
                packageName = packageName,
                installed = true,
                isRepackaged = true
        )
        if (appStatus != null) {
            appStatus.update(status)
        } else {
            appStatus = status
        }
        appStatus.storeStateApp()

        Toasty.success(this, "Privacy Settings Updated").show()
    }

    private fun startRepacking() {

        val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
        alertDialogBuilder
                .setTitle(getString(R.string.android_versione_7))
                .setMessage(getString(R.string.ask_app_modification))
                .setPositiveButton("Yes") { dialog, _ ->
                    setUpOutputFolderThenRepack()
                }
                .setNeutralButton(R.string.action_try_without_modification) { _, _ ->
                    interceptWithoutModification()
                }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }

        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    /** Setup step: pick where decompile/build artifacts land before the repack actually starts. */
    private fun setUpOutputFolderThenRepack() {
        Toasty.info(this, getString(R.string.output_folder_setup_message)).show()
        FolderPicker.choose(this, HideDroidStorage.prepareBaseDir(this)) { chosenDir ->
            HideDroidStorage.setOutputFolder(this, chosenDir)

            apkPath = relocate(apkPath!!, chosenDir)
            splitApkPaths = splitApkPaths.map { relocate(it, chosenDir) }
            performRepacking(apkPath!!, splitApkPaths)
        }
    }

    private fun relocate(path: String, chosenDir: File): String {
        val current = File(path)
        val relocated = File(chosenDir, current.name)
        if (relocated.absolutePath != current.absolutePath) {
            Utilities.copyFile(current, relocated)
        }
        return relocated.absolutePath
    }

    /**
     * Tracks the app without repackaging it. Cleartext traffic is always
     * intercepted; TLS traffic is intercepted only when the app already trusts
     * user certificates (targetSdk lower than 24, a networkSecurityConfig that
     * accepts user CAs, or the HideDroid CA installed in the system store).
     */
    private fun interceptWithoutModification() {
        TrackingActions.trackWithoutRepack(this, appPackageNameToModify!!, privacyLevel)
        changeButtonSettings()
        Toasty.info(this, getString(R.string.intercept_without_modification_result)).show()
    }

    private fun performRepacking(apkPath: String, splitApkPaths: List<String> = emptyList()) {
        TrackingActions.startRepack(this, appPackageNameToModify!!, apkPath, splitApkPaths)
    }

    private fun installApk() {
        InstallActions.installSignedApk(this, appPackageNameToModify!!, newApkPath!!, privacyLevel, uninstallLauncher, installLauncher)
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
        when (privacyLevel) {
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
        if (appPrivacySetting != null && appPrivacySetting!!.isInstalled) {
            privacySeekBar!!.progress = appPrivacySetting!!.privacyLevel
            privacyLevel = appPrivacySetting!!.privacyLevel
            updateSeekBar(privacyLevel)
        }
    }

}