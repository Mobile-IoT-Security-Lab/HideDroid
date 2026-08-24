package it.unige.hidedroid.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import android.transition.Explode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.github.megatronking.netbare.ssl.JKS
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import es.dmoral.toasty.Toasty
import io.realm.Realm
import it.unige.hidedroid.BuildConfig
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.interceptor.HttpInterceptor
import it.unige.hidedroid.log.DataExport
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.log.LoggerToJson
import it.unige.hidedroid.realmdatahelper.*
import com.dave.realmdatahelper.debug.Request as DebugRequest
import com.dave.realmdatahelper.debug.Response as DebugResponse
import com.dave.realmdatahelper.debug.Error as DebugError
import it.unige.hidedroid.log.Error as LogError
import it.unige.hidedroid.service.ServiceAnonymization
import it.unige.hidedroid.utils.HideDroidStorage
import it.unige.hidedroid.utils.RootUtil
import it.unige.hidedroid.utils.Utilities
import it.unige.hidedroid.viewmodels.StatusVPNViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), NetBareListener, PermissionListener {

    companion object {
        private val TAG = MainActivity::class.java.name
    }

    private lateinit var vpnPrepLauncher: ActivityResultLauncher<Intent>
    private lateinit var certInstallLauncher: ActivityResultLauncher<Intent>
    private lateinit var certExportLauncher: ActivityResultLauncher<Uri?>
    private lateinit var exportDirLauncher: ActivityResultLauncher<Uri?>

    private var FOLDER_FILE: File? = null
    private var myViewModel: StatusVPNViewModel? = null
    private var appListTracked: MutableList<String>? = null
    private lateinit var mNetBare: NetBare
    private lateinit var trackersMappingDomainName: TrackersMappingDomainName
    private lateinit var isDebugEnabled: AtomicBoolean
    private lateinit var listener: SharedPreferencesUpdateListener
    private lateinit var handle_vpn_button: android.widget.Button
    private lateinit var addAppButton: FloatingActionButton
    private var verifyAfterExport = false

    interface SharedPreferencesUpdateListener {
        fun onUpdatePreferences(isDebugEnabled: Boolean)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnPrepLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> prepareNetBare(false)
                Activity.RESULT_CANCELED -> myViewModel!!.isClicked = false
            }
        }

        certInstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toasty.success(this, getString(R.string.ca_install_user_done)).show()
                if (RootUtil.isDeviceRooted && !Utilities.isModernAndroid()) {
                    installSystemCA()
                }
                updateVpnButtonState()
            }
        }

        certExportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                exportCertificateToUri(treeUri)
            } else {
                Toasty.error(this, getString(R.string.ca_export_failed)).show()
            }
        }

        exportDirLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                persistExportDirUri(treeUri)
            } else {
                Toasty.error(this, getString(R.string.ca_export_failed)).show()
            }
        }

        // Animation
        with(window) {
            requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            // set the transition to be shown when the user enters this activity
            enterTransition = Explode()
            // set the transition to be shown when the user leaves this activity
            exitTransition = Explode()
        }

        setContentView(R.layout.activity_main)
        handle_vpn_button = findViewById(R.id.handle_vpn_button)
        addAppButton = findViewById(R.id.addAppButton)

        // get view model and FOLDER_FILE
        myViewModel = ViewModelProvider(this).get(StatusVPNViewModel::class.java)
        FOLDER_FILE = HideDroidStorage.prepareBaseDir(this)

        // check permission
        Dexter.withContext(this)
                .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .withListener(this)
                .check()

        // SETUP VPN
        mNetBare = NetBare.get()
        myViewModel!!.isClicked = mNetBare.isActive
        mNetBare.registerNetBareListener(this)

        (this.application as HideDroidApplication).serviceAnonymizationReady = object : HideDroidApplication.ServiceAnonymizationReady {
            override fun onReadyService() {
                prepareNetBare(false)
            }
        }

        // Setup Listener
        handle_vpn_button.setOnClickListener {
            mNetBare = NetBare.get()
            val certificateInstalled = JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)
            if (!mNetBare.isActive && !myViewModel!!.isClicked && certificateInstalled) {
                myViewModel!!.isClicked = true
                Toasty.success(application, "Start Incognito Mode").show()
                changeStateButton("ON", getColor(R.color.seek_bar_progress_high), false)

                val intent = Intent(this, ServiceAnonymization::class.java)
                ContextCompat.startForegroundService(this, intent)
            } else {
                if (!certificateInstalled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P + 2) {
                        Toasty.warning(application, "Install Certificate From SdCard").show()
                    }
                    changeStateButton("NOT READY", getColor(R.color.seek_bar_progress_medium), true)
                    myViewModel!!.isClicked = false

                    prepareNetBare(true)
                } else if (mNetBare.isActive && myViewModel!!.isClicked) {
                    Toasty.error(application, "Off Incognito Mode").show()
                    changeStateButton("OFF", getColor(R.color.seek_bar_progress_none), false)
                    myViewModel!!.isClicked = false
                    mNetBare.stop()

                    val intent = Intent(this, ServiceAnonymization::class.java)
                    stopService(intent)
                } else {
                    Toasty.warning(application, "You should wait").show()
                }
            }
        }
        addAppButton.setOnClickListener {
            val intent = Intent(this, SelectAppActivity::class.java)
            startActivity(intent)
        }

        isDebugEnabled = (this.application as HideDroidApplication).isDebugEnabled
        listener = (this.application as HideDroidApplication).sharedPreferencesUpdateListener
        invalidateOptionsMenu()
    }

    private fun changeStateButton(newState: String, color: Int, warning_button: Boolean) {
        if (warning_button) {
            this.runOnUiThread {
                handle_vpn_button.text = newState
                handle_vpn_button.textSize = 40.0F
                handle_vpn_button.setBackgroundColor(color)
            }
        } else {
            this.runOnUiThread {
                handle_vpn_button.text = newState
                handle_vpn_button.textSize = 48.0F
                handle_vpn_button.setBackgroundColor(color)
            }
        }
    }

    private fun updateVpnButtonState() {
        mNetBare = NetBare.get()
        when {
            !JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS) -> {
                changeStateButton("NOT READY", getColor(R.color.seek_bar_progress_medium), true)
            }
            mNetBare.isActive && myViewModel!!.isClicked -> {
                changeStateButton("ON", getColor(R.color.seek_bar_progress_high), false)
            }
            else -> {
                changeStateButton("OFF", getColor(R.color.seek_bar_progress_none), false)
            }
        }
    }

    private fun prepareNetBare(checkForInstallCA: Boolean) {
        if (!JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)) {
            try {
                JKS.install(this, HideDroidApplication.JSK_ALIAS, HideDroidApplication.JSK_ALIAS, certInstallLauncher)
                myViewModel!!.isClicked = false
            } catch (e: IOException) {
                Toasty.error(this, e.toString()).show()
            }
            return
        }

        if (checkForInstallCA) {
            return
        }

        val intent = NetBare.get().prepare()
        if (intent != null) {
            vpnPrepLauncher.launch(intent)
            return
        }

        if (this.trackersMappingDomainName != null && this.appListTracked != null) {
            var config = NetBareConfig.defaultHttpConfigWithConnectivityManager(
                    HideDroidApplication.getInstance().getJSK(),
                    interceptorFactories(),
                    HideDroidApplication.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE)
                            as ConnectivityManager?,
                    this.appListTracked
            )

            myViewModel!!.isClicked = true
            mNetBare.start(config)
        }
    }

    override fun onResume() {
        updateVpnButtonState()

        lifecycleScope.launch(Dispatchers.IO) {
            updateDBPackageNamePrivacyLevel()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            trackersMappingDomainName = TrackersMappingDomainName(HashMap())
            trackersMappingDomainName.populateMapping("trackers.json", this@MainActivity)
        }

        Dexter.withContext(this)
                .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .withListener(this)
                .check()

        invalidateOptionsMenu()

        super.onResume()
    }

    /*override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val telephonyManager: TelephonyManager = this.getSystemService(Application.TELEPHONY_SERVICE) as TelephonyManager
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "Phone State permissions not given")
                }
            } else {
                Toasty.warning(this, "Grant Phone Permissions to activate Incognito Mode").show()
                this.finish()
            }
        }
    }*/

    override fun onRestart() {
        LoggerHideDroid.i(TAG, "onRestart")
        updateVpnButtonState()
        super.onRestart()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.nav_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (isDebugEnabled.get()) {
            menu?.findItem(R.id.debug_button)?.setIcon(R.drawable.ic_baseline_bug_report_enabled_27)
        } else {
            menu?.findItem(R.id.debug_button)?.setIcon(R.drawable.ic_baseline_bug_report_disabled_27)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {

            R.id.debug_button -> {
                if (!isDebugEnabled.get()) {
                    isDebugEnabled.set(true)
                    listener.onUpdatePreferences(isDebugEnabled.get())
                    item.setIcon(R.drawable.ic_baseline_bug_report_enabled_27)
                    Toasty.success(this, "Debug Mode ON").show()
                } else {
                    isDebugEnabled.set(false)
                    listener.onUpdatePreferences(isDebugEnabled.get())
                    item.setIcon(R.drawable.ic_baseline_bug_report_disabled_27)
                    Toasty.error(this, "Debug Mode OFF").show()
                }
            }

            R.id.clean_dir -> {
                val systemCaName = systemCaFileName()
                if (systemCaName != null && RootUtil.isDeviceRooted) {
                    confirmDeleteSystemCA(systemCaName)
                } else {
                    handleCleanDirectory()
                }
            }

            R.id.installCACertificate -> handleCertificateInstallation()
            R.id.extractDB -> runExportWithDir { exportUri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val dataExport = DataExport(this@MainActivity, exportUri)
                        dataExport.exportAnalyticsRequest()
                        dataExport.exportRealmDB()
                        withContext(Dispatchers.Main) {
                            Toasty.success(this@MainActivity, getString(R.string.export_db_done)).show()
                        }
                    } catch (e: Exception) {
                        LoggerHideDroid.e(TAG, "exportDB error: $e")
                        withContext(Dispatchers.Main) {
                            Toasty.error(this@MainActivity, "Export failed: ${e.message}").show()
                        }
                    }
                }
            }

            R.id.extractDebugLogs -> {
                if (!isDebugEnabled.get()) {
                    Toasty.warning(this, "Enable Debug Mode First!").show()
                } else {
                    runExportWithDir { exportUri ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val app = this@MainActivity.application as HideDroidApplication
                                val dataExport = DataExport(this@MainActivity, exportUri)

                                Realm.getInstance(app.realmConfigLog).use { realm ->
                                    // Information from Request table
                                    val requestClass = com.dave.realmdatahelper.debug.Request::class.java
                                    val allRequest = realm.copyFromRealm(realm.where(requestClass).findAll())
                                    val totalPackets = allRequest.size
                                    val numberPacketsTracked = realm.where(requestClass).equalTo("isTracked", true).findAll().size
                                    val numberPacketsPrivate = realm.where(requestClass).equalTo("isPrivate", true).findAll().size

                                    val allHostsSet = mutableSetOf<String>()
                                    val trackedHostsSet = mutableSetOf<String>()
                                    val privateHostsSet = mutableSetOf<String>()
                                    for (request in allRequest) {
                                        allHostsSet.add(request.host)
                                        when {
                                            request.isTracked -> trackedHostsSet.add(request.host)
                                            request.isPrivate -> privateHostsSet.add(request.host)
                                        }
                                    }

                                    // Information from Error table
                                    val errorClass = com.dave.realmdatahelper.debug.Error::class.java
                                    val errorsRealm = realm.copyFromRealm(realm.where(errorClass).findAll())
                                    val errors = mutableListOf<LogError>()
                                    for (error in errorsRealm) {
                                        errors.add(LogError(error.app, error.host, error.headers, error.body, error.error))
                                    }

                                    // Information from Response table
                                    val responseClass = com.dave.realmdatahelper.debug.Response::class.java
                                    val acceptedCodes = listOf("200", "201", "203", "204", "206", "207", "226")
                                    val responseTable = realm.copyFromRealm(realm.where(responseClass).findAll())
                                    val numberAcceptedRequest = responseTable.count { it.code in acceptedCodes }
                                    val numberRefusedRequest = responseTable.size - numberAcceptedRequest

                                    // Create JSON debug file
                                    val loggerToJson = LoggerToJson(totalPackets, numberPacketsTracked, numberPacketsPrivate,
                                            allHostsSet.toMutableList(), trackedHostsSet.toMutableList(),
                                            privateHostsSet.toMutableList(), errors, numberAcceptedRequest, numberRefusedRequest)
                                    val debugLogs = Gson().toJson(loggerToJson)
                                    dataExport.exportDebugLogs(debugLogs)

                                    dataExport.exportRealmDebugLogs(app.realmConfigLog)
                                    dataExport.exportDebugLogsRequestCsv(app.realmConfigLog)
                                    dataExport.exportDebugLogsErrorCsv(app.realmConfigLog)
                                }

                                withContext(Dispatchers.Main) {
                                    Toasty.success(this@MainActivity, getString(R.string.export_debug_logs_done)).show()
                                }
                            } catch (e: Exception) {
                                LoggerHideDroid.e(TAG, "extractDebugLogs error: $e")
                                withContext(Dispatchers.Main) {
                                    Toasty.error(this@MainActivity, "Debug logs export failed: ${e.message}").show()
                                }
                            }
                        }
                    }
                }
            }

            R.id.extractDGH -> runExportWithDir { exportUri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val dataExport = DataExport(this@MainActivity, exportUri)
                        dataExport.exportRealmDGH((this@MainActivity.application as HideDroidApplication).realmConfigDGH)
                        withContext(Dispatchers.Main) {
                            Toasty.success(this@MainActivity, getString(R.string.export_dgh_done)).show()
                        }
                    } catch (e: Exception) {
                        LoggerHideDroid.e(TAG, "extractDGH error: $e")
                        withContext(Dispatchers.Main) {
                            Toasty.error(this@MainActivity, "DGH export failed: ${e.message}").show()
                        }
                    }
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun interceptorFactories(): List<HttpInterceptorFactory> {
        val jsonString = this.assets.open("private_params.json").bufferedReader().use { it.readText() }
        val objectList = Gson().fromJson(jsonString, Array<String>::class.java).asList()
        val interceptorHttp = HttpInterceptor.createFactory(packageManager, this.trackersMappingDomainName, this.appListTracked!!,
                (application as HideDroidApplication).realmConfigPrivateTracker,
                (application as HideDroidApplication).realmConfigLog, objectList, isDebugEnabled,
                (this.application as HideDroidApplication).serviceAnonymization!!.dataAnonymizer)
        return listOf(interceptorHttp)
    }

    private fun handleCertificateInstallation() {
        val userInstalled = JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)
        val systemCaName = systemCaFileName()
        val systemInstalled = systemCaName != null
        val rooted = RootUtil.isDeviceRooted
        val modernAndroid = Utilities.isModernAndroid()

        when {
            systemInstalled && userInstalled -> showCertificateStatusDialog(
                    R.string.ca_status_both,
                    R.string.ca_location_system_store,
                    canInstallSystem = false,
                    canInstallUser = false,
                    modernAndroid = modernAndroid,
                    rooted = rooted,
                    canDeleteSystem = rooted,
                    systemCaName = systemCaName,
                    canDeleteUser = true
            )
            systemInstalled -> showCertificateStatusDialog(
                    R.string.ca_status_system_only,
                    R.string.ca_location_system_store,
                    canInstallSystem = false,
                    canInstallUser = false,
                    modernAndroid = modernAndroid,
                    rooted = rooted,
                    canDeleteSystem = rooted,
                    systemCaName = systemCaName
            )
            userInstalled && rooted -> showCertificateStatusDialog(
                    R.string.ca_status_user_only,
                    R.string.ca_location_user_store,
                    canInstallSystem = !modernAndroid,
                    canInstallUser = false,
                    modernAndroid = modernAndroid,
                    rooted = rooted,
                    canDeleteUser = true
            )
            userInstalled -> showCertificateStatusDialog(
                    R.string.ca_status_user_only,
                    R.string.ca_location_user_store,
                    canInstallSystem = false,
                    canInstallUser = false,
                    modernAndroid = modernAndroid,
                    rooted = rooted,
                    canDeleteUser = true
            )
            else -> showCertificateStatusDialog(
                    R.string.ca_status_none,
                    R.string.ca_location_user_store,
                    canInstallSystem = rooted && !modernAndroid,
                    canInstallUser = true,
                    modernAndroid = modernAndroid,
                    rooted = rooted
            )
        }
    }

    private fun isSystemCaInstalled(): Boolean {
        return systemCaFileName() != null
    }

    private fun systemCaFileName(): String? {
        return try {
            val hashNameSystemCA = HideDroidApplication.getInstance().getJSK().hashSystemCACertificate
            if (!hashNameSystemCA.isNullOrBlank() && RootUtil.isSystemCaInstalled(hashNameSystemCA)) {
                hashNameSystemCA
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun showCertificateStatusDialog(
            messageRes: Int,
            locationRes: Int,
            canInstallSystem: Boolean,
            canInstallUser: Boolean,
            modernAndroid: Boolean,
            rooted: Boolean,
            canDeleteSystem: Boolean = false,
            systemCaName: String? = null,
            canDeleteUser: Boolean = false
    ) {
        val actions = mutableListOf<String>()
        val callbacks = mutableListOf<() -> Unit>()

        if (canInstallSystem) {
            actions.add(getString(R.string.ca_status_system_install_prompt))
            callbacks.add {
                val progress = showCertificateProgress(R.string.ca_install_system_progress)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        installSystemCA()
                    } finally {
                        withContext(Dispatchers.Main) { progress.dismiss() }
                    }
                }
            }
        }
        if (canDeleteSystem && systemCaName != null) {
            actions.add(getString(R.string.ca_delete_system_prompt))
            callbacks.add { confirmDeleteSystemCA(systemCaName) }
        }
        if (canDeleteUser) {
            actions.add(getString(R.string.ca_delete_user_prompt))
            callbacks.add { confirmDeleteUserCA() }
        }
        if (canInstallUser) {
            actions.add(getString(R.string.ca_status_user_install_prompt))
            callbacks.add {
                val progress = showCertificateProgress(R.string.ca_install_user_progress)
                try {
                    JKS.install(this, HideDroidApplication.JSK_ALIAS, HideDroidApplication.JSK_ALIAS, certInstallLauncher)
                } finally {
                    progress.dismiss()
                }
            }
        }
        actions.add(getString(R.string.ca_verify_prompt))
        callbacks.add { verifyCertificateInstallation() }
        actions.add(getString(R.string.ca_export_prompt))
        callbacks.add { openCertificateExportPicker() }
        actions.add(getString(R.string.ca_force_reinstall_prompt))
        callbacks.add { confirmForceReinstallCertificate() }

        var message = getString(messageRes) + "\n\nLocation: " + getString(locationRes)
        if (modernAndroid && rooted) {
            message += "\n\n" + getString(R.string.install_system_ca_modern)
        }
        if (!systemCaName.isNullOrBlank()) {
            message += "\n\n" + getString(R.string.ca_system_file_label, systemCaName)
        }

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_certificate_actions, null)
        view.findViewById<TextView>(R.id.caStatusMessage).text = message
        val container = view.findViewById<LinearLayout>(R.id.caActionsContainer)

        lateinit var dialog: AlertDialog
        for (i in actions.indices) {
            val button = android.widget.Button(this).apply {
                text = actions[i]
                setTextColor(resources.getColor(android.R.color.black, theme))
                textSize = 16f
                setPadding(0, 24, 0, 24)
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialog.dismiss()
                    callbacks[i].invoke()
                }
            }
            container.addView(button)
            if (i < actions.lastIndex) {
                container.addView(View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            .apply { setMargins(0, 8, 0, 8) }
                    setBackgroundColor(resources.getColor(android.R.color.darker_gray, theme))
                })
            }
        }

        dialog = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(R.string.ca_status_title)
                .setView(view)
                .setNegativeButton("Close") { d, _ -> d.cancel() }
                .create()
        dialog.show()
    }

    private fun confirmForceReinstallCertificate() {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(R.string.ca_status_title)
                .setMessage(R.string.ca_force_reinstall_confirm)
                .setPositiveButton("Yes") { _, _ -> forceReinstallCertificate() }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }
                .create()
                .show()
    }

    private fun forceReinstallCertificate() {
        val progress = showCertificateProgress(R.string.ca_force_reinstall_progress)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val newJks = HideDroidApplication.getInstance().regenerateJSK()
                var attempts = 0
                while (!newJks.isInstalled && attempts < 30) {
                    Thread.sleep(200)
                    attempts++
                }
                if (!newJks.isInstalled) {
                    withContext(Dispatchers.Main) {
                        Toasty.error(this@MainActivity, getString(R.string.ca_force_reinstall_failed)).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    Toasty.success(this@MainActivity, getString(R.string.ca_force_reinstall_done)).show()
                    if (Utilities.isModernAndroid()) {
                        verifyAfterExport = true
                        openCertificateExportPicker()
                    } else {
                        installSystemCA()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@MainActivity, getString(R.string.ca_force_reinstall_failed)).show()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun openCertificateExportPicker() {
        try {
            certExportLauncher.launch(null)
        } catch (e: Exception) {
            Toasty.error(this, getString(R.string.ca_export_failed)).show()
        }
    }

    private fun getExportDirUri(): Uri? {
        val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
        val uriString = sharedPref.getString(getString(R.string.key_export_dir_uri), null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            null
        }
    }

    private fun persistExportDirUri(treeUri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val sharedPref = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString(getString(R.string.key_export_dir_uri), treeUri.toString())
                apply()
            }
            val dir = DocumentFile.fromTreeUri(this, treeUri)
            Toasty.success(this, "Export folder set to ${dir?.name ?: treeUri}").show()
            // Run the pending export callback if one is waiting.
            pendingExportCallback?.invoke(treeUri)
            pendingExportCallback = null
        } catch (e: SecurityException) {
            Toasty.error(this, getString(R.string.export_dir_persist_failed)).show()
            pendingExportCallback = null
        }
    }

    private var pendingExportCallback: ((Uri) -> Unit)? = null

    private fun runExportWithDir(action: (Uri) -> Unit) {
        val exportUri = getExportDirUri()
        if (exportUri == null) {
            pendingExportCallback = action
            try {
                exportDirLauncher.launch(null)
            } catch (e: Exception) {
                Toasty.error(this, getString(R.string.export_dir_missing)).show()
                pendingExportCallback = null
            }
        } else {
            action(exportUri)
        }
    }

    private fun exportCertificateToUri(treeUri: Uri) {
        val pemFile = HideDroidApplication.getInstance().getJSK().aliasFile(JKS.KEY_PEM_FILE_EXTENSION)
        if (!pemFile.exists()) {
            Toasty.error(this, getString(R.string.ca_export_failed)).show()
            return
        }

        val progress = showCertificateProgress(R.string.ca_export_progress)
        lifecycleScope.launch(Dispatchers.IO) {
            val shouldVerify = verifyAfterExport
            try {
                val fileName = HideDroidApplication.JSK_ALIAS + "_ca.crt"
                val mimeType = "application/x-x509-ca-cert"

                // OpenDocumentTree returns a tree URI; DocumentsContract.createDocument()
                // expects a document URI derived from that tree.
                val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                val newUri = DocumentsContract.createDocument(contentResolver, parentDocumentUri, mimeType, fileName)

                if (newUri == null) {
                    withContext(Dispatchers.Main) {
                        Toasty.error(this@MainActivity, getString(R.string.ca_export_failed)).show()
                    }
                    return@launch
                }

                FileInputStream(pemFile).use { input ->
                    contentResolver.openOutputStream(newUri)?.use { output ->
                        input.copyTo(output)
                        output.flush()
                    } ?: throw IOException("Unable to open output stream")
                }

                withContext(Dispatchers.Main) {
                    if (shouldVerify) {
                        verifyAfterExport = false
                        Toasty.success(this@MainActivity, getString(R.string.ca_force_reinstall_export_done)).show()
                        verifyCertificateInstallation()
                    } else {
                        Toasty.success(this@MainActivity, getString(R.string.ca_export_done, fileName)).show()
                    }
                }
            } catch (e: IOException) {
                verifyAfterExport = false
                withContext(Dispatchers.Main) {
                    Toasty.error(this@MainActivity, getString(R.string.ca_export_failed)).show()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun verifyCertificateInstallation() {
        val progress = showCertificateProgress(R.string.ca_verify_progress)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val userInstalled = JKS.isInstalledOnDevice(this@MainActivity, HideDroidApplication.JSK_ALIAS)
                val systemInstalled = isSystemCaInstalled()
                withContext(Dispatchers.Main) {
                    when {
                        systemInstalled && userInstalled -> {
                            Toasty.success(this@MainActivity, getString(R.string.ca_status_both)).show()
                        }
                        systemInstalled -> {
                            Toasty.success(this@MainActivity, getString(R.string.ca_status_system_only)).show()
                        }
                        userInstalled -> {
                            Toasty.success(this@MainActivity, getString(R.string.ca_status_user_only)).show()
                        }
                        else -> {
                            Toasty.warning(this@MainActivity, getString(R.string.ca_status_none)).show()
                        }
                    }
                    updateVpnButtonState()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@MainActivity, getString(R.string.ca_export_failed)).show()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun showCertificateProgress(messageRes: Int): AlertDialog {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        view.findViewById<TextView>(R.id.progressMessage).text = getString(messageRes)
        return AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setView(view)
                .setCancelable(false)
                .create()
                .also { it.show() }
    }

    private fun confirmDeleteSystemCA(nameCert: String) {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(R.string.ca_status_title)
                .setMessage(getString(R.string.ca_delete_system_confirm, nameCert))
                .setPositiveButton("Yes") { _, _ -> deleteSystemCA(nameCert) }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }
                .create()
                .show()
    }

    private fun deleteSystemCA(nameCert: String) {
        if (!RootUtil.isDeviceRooted) {
            Toasty.error(this, getString(R.string.function_system_ca_string_not_available)).show()
            return
        }
        val progress = showCertificateProgress(R.string.ca_delete_system_progress)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!RootUtil.canRunRootCommands()) {
                    withContext(Dispatchers.Main) {
                        Toasty.error(this@MainActivity, "Root access denied").show()
                    }
                    return@launch
                }
                val deleted = RootUtil.deleteSystemCA(nameCert)
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toasty.success(this@MainActivity, getString(R.string.ca_delete_system_done)).show()
                        val sharedPref = this@MainActivity.getSharedPreferences(
                                getString(R.string.preference_file_key), Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            putBoolean(getString(R.string.key_root_certificate_preference), false)
                            commit()
                        }
                    } else {
                        Toasty.error(this@MainActivity, getString(R.string.ca_delete_system_failed)).show()
                    }
                    updateVpnButtonState()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun confirmDeleteUserCA() {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(R.string.ca_status_title)
                .setMessage(R.string.ca_delete_user_confirm)
                .setPositiveButton("Yes") { _, _ -> deleteUserCA() }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }
                .create()
                .show()
    }

    private fun deleteUserCA() {
        val progress = showCertificateProgress(R.string.ca_delete_user_progress)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val hashNameSystemCA = HideDroidApplication.getInstance().getJSK().hashSystemCACertificate
                if (hashNameSystemCA.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toasty.error(this@MainActivity, getString(R.string.ca_delete_user_failed)).show()
                    }
                    return@launch
                }

                // Android has no public API to remove a user CA. Try a root deletion first;
                // if root is not available or fails, fall back to opening the system security
                // settings so the user can remove it manually from the user certificate list.
                val deleted = RootUtil.isDeviceRooted && RootUtil.canRunRootCommands() &&
                        RootUtil.deleteUserCA(hashNameSystemCA)
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toasty.success(this@MainActivity, getString(R.string.ca_delete_user_done)).show()
                        updateVpnButtonState()
                    } else {
                        openSecuritySettingsForCA()
                    }
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun openSecuritySettingsForCA() {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(R.string.ca_status_title)
                .setMessage(R.string.ca_delete_user_manual_message)
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e: Exception) {
                        Toasty.error(this, "Unable to open security settings").show()
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                .create()
                .show()
    }

    private fun handleCleanDirectory() {
        if (handle_vpn_button.text == "ON") {
            Toasty.warning(this, "You have to stop Incognito Mode first").show()
            return
        }
        Dexter.withContext(this)
                .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .withListener(this)
                .check()
        val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)

        alertDialogBuilder
                .setTitle("Clean Directory HideDroid")
                .setMessage("Would you like delete all files within HideDroid directory?")
                .setPositiveButton("Yes") { dialog, id ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        FOLDER_FILE!!.deleteRecursively()
                        prepareFileAndFolders()

                        val realm = Realm.getDefaultInstance()

                        realm.use { realm ->
                            // cancelliamo anche i pacchetti raccolti dalle tabelle EventBuffer e AnalyticsRequest
                            realm.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.hidedroid.EventBuffer::class.java) }
                            realm.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.hidedroid.AnalyticsRequest::class.java) }
                        }

                        Realm.getInstance((application as HideDroidApplication).realmConfigLog).use { realmDebugLogs ->
                            // cancelliamo le informazioni raccolte all'interno delle tabelle Request, Error e Response
                            realmDebugLogs.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.debug.Request::class.java) }
                            realmDebugLogs.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.debug.Error::class.java) }
                            realmDebugLogs.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.debug.Response::class.java) }
                        }
                        withContext(Dispatchers.Main) {
                            Toasty.success(application, "Directory Cleaned").show()
                        }
                    }

                }
                .setNegativeButton("No") { dialog, id -> dialog.cancel() }

        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    private fun installSystemCA() {
        if (!RootUtil.isDeviceRooted) {
            Toasty.error(this, getString(R.string.function_system_ca_string_not_available)).show()
            return
        }

        val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
        alertDialogBuilder
                .setTitle("Root Device Detected")
                .setMessage(getString(R.string.install_system_ca))
                .setPositiveButton("Yes") { _, _ ->
                    val progress = showCertificateProgress(R.string.ca_install_system_progress)
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            if (!RootUtil.canRunRootCommands()) {
                                withContext(Dispatchers.Main) {
                                    Toasty.error(this@MainActivity, "Root access denied").show()
                                }
                                return@launch
                            }
                            val hashNameSystemCA = HideDroidApplication.getInstance().getJSK().hashSystemCACertificate
                            val pemSystemCA = HideDroidApplication.getInstance().getJSK().pemCACertificate
                            val installedSystemCertificate = RootUtil.installSystemCA(hashNameSystemCA, pemSystemCA)
                            withContext(Dispatchers.Main) {
                                if (installedSystemCertificate) {
                                    Toasty.success(this@MainActivity, getString(R.string.ca_install_system_done)).show()
                                    val sharedPref = this@MainActivity.getSharedPreferences(
                                            getString(R.string.preference_file_key), Context.MODE_PRIVATE)
                                    with(sharedPref.edit()) {
                                        putBoolean(getString(R.string.key_root_certificate_preference), true)
                                        commit()
                                    }
                                } else {
                                    Toasty.error(this@MainActivity, getString(R.string.ca_install_system_failed)).show()
                                }
                            }
                        } finally {
                            withContext(Dispatchers.Main) { progress.dismiss() }
                        }
                    }
                }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }

        alertDialogBuilder.create().show()
    }

    private suspend fun updateDBPackageNamePrivacyLevel() {
        // This dispatcher is optimized to perform disk or network I/O outside of the main thread
        // this function update app installed or removed by the user to update the db
        withContext(Dispatchers.IO) {
            appListTracked = mutableListOf<String>()
            // LoggerHideDroid.d(TAG, "START updateDBPackageNamePrivacyLevel cororoutines")
            var appList = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            var appListTrackedOld = UtilitiesStoreDataOnRealmDb.getAllPrivacySettingApp()
            //var appListTrackedOld = UtilitiesStoreDataOnDb.getAllPackageNamePrivacyLevel()
            for (appTracked in appListTrackedOld) {
                var found = false
                for (appInstalled in appList) {
                    if ((ApplicationInfo.FLAG_SYSTEM and appInstalled.flags) == 0 &&
                            appTracked.packageName == appInstalled.packageName) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    appTracked.deleteThisFromRealm()
                    //appTracked.delete()
                }
            }
        }
    }

    private fun prepareFileAndFolders() {
        val folder = HideDroidStorage.prepareBaseDir(this)
        if (!folder.exists()) {
            folder.mkdirs()
        }
    }

    override fun onPermissionGranted(response: PermissionGrantedResponse?) {
        prepareFileAndFolders()
    }

    override fun onPermissionDenied(response: PermissionDeniedResponse?) {
        if (response!!.isPermanentlyDenied) {
            Toasty.error(this, "Permission permanently denied.").show()
            val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toasty.warning(this, "Permission denied.").show()
        }
    }

    override fun onPermissionRationaleShouldBeShown(permission: PermissionRequest?, token: PermissionToken?) {
        token!!.continuePermissionRequest()
    }

    override fun onDestroy() {
        mNetBare.unregisterNetBareListener(this)
        super.onDestroy()
    }

    override fun onServiceStarted() {
        this.runOnUiThread(Runnable {
            LoggerHideDroid.i(TAG, "SERVICE VPN STARTED")
            myViewModel!!.isClicked = true
        })
        changeStateButton("ON", getColor(R.color.seek_bar_progress_high), false)
    }

    override fun onServiceStopped() {
        this.runOnUiThread(Runnable {
            LoggerHideDroid.i(TAG, "SERVICE VPN STOPPED")
            myViewModel!!.isClicked = false
        })
        changeStateButton("OFF", getColor(R.color.seek_bar_progress_none), false)
    }
}