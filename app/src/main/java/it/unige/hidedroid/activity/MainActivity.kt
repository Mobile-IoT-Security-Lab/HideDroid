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
import android.os.Environment
import android.provider.Settings
import android.transition.Explode
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.NetBareConfig
import com.github.megatronking.netbare.NetBareListener
import com.github.megatronking.netbare.http.HttpInterceptorFactory
import com.github.megatronking.netbare.ssl.JKS
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
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.realmdatahelper.*
import it.unige.hidedroid.service.ServiceAnonymization
import it.unige.hidedroid.utils.RootUtil
import it.unige.hidedroid.utils.Utilities
import it.unige.hidedroid.viewmodels.StatusVPNViewModel
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), NetBareListener, PermissionListener {

    companion object {
        private const val REQUEST_CODE_PREPARE = 1
        const val REQUEST_CODE_CERTIFICATE_INSTALLED = 30
        private val TAG = MainActivity::class.java.name
    }

    private var FOLDER_FILE: File? = null
    private var myViewModel: StatusVPNViewModel? = null
    private var appListTracked: MutableList<String>? = null
    private lateinit var mNetBare: NetBare
    private lateinit var trackersMappingDomainName: TrackersMappingDomainName
    private lateinit var isDebugEnabled: AtomicBoolean
    private lateinit var listener: SharedPreferencesUpdateListener

    interface SharedPreferencesUpdateListener {
        fun onUpdatePreferences(isDebugEnabled: Boolean)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Animation
        with(window) {
            requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
            // set the transition to be shown when the user enters this activity
            enterTransition = Explode()
            // set the transition to be shown when the user leaves this activity
            exitTransition = Explode()
        }

        setContentView(R.layout.activity_main)

        // get view model and FOLDER_FILE
        myViewModel = ViewModelProvider(this).get(StatusVPNViewModel::class.java)
        FOLDER_FILE = File(Environment.getExternalStorageDirectory(), "HideDroid")

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
                startService(intent)
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

    private fun prepareNetBare(checkForInstallCA: Boolean) {
        if (!JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)) {
            try {
                JKS.install(this, HideDroidApplication.JSK_ALIAS, HideDroidApplication.JSK_ALIAS)
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
            startActivityForResult(intent, REQUEST_CODE_PREPARE)
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
        mNetBare = NetBare.get()
        if (!JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)) {
            changeStateButton("NOT READY", getColor(R.color.seek_bar_progress_medium), true)
        } else if (mNetBare.isActive && myViewModel!!.isClicked) {
            changeStateButton("ON", getColor(R.color.seek_bar_progress_high), false)
        } else {
            changeStateButton("OFF", getColor(R.color.seek_bar_progress_none), false)
        }

        GlobalScope.launch(Dispatchers.IO) {
            updateDBPackageNamePrivacyLevel()
        }

        GlobalScope.launch(Dispatchers.IO) {
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
        mNetBare = NetBare.get()
        if (!JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)) {
            changeStateButton("NOT READY", getColor(R.color.seek_bar_progress_medium), true)
        } else if (mNetBare.isActive && myViewModel!!.isClicked) {
            changeStateButton("ON", getColor(R.color.seek_bar_progress_high), false)
        } else {
            changeStateButton("OFF", getColor(R.color.seek_bar_progress_none), false)
        }
        super.onRestart()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.nav_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        /*if (isDebugEnabled.get()) {
            menu?.findItem(R.id.debug_button)?.setIcon(R.drawable.ic_baseline_bug_report_enabled_27)
        } else {
            menu?.findItem(R.id.debug_button)?.setIcon(R.drawable.ic_baseline_bug_report_disabled_27)
        }*/
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item!!.itemId) {

            /*R.id.debug_button -> {
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
            }*/

            R.id.clean_dir -> {
                if (handle_vpn_button.text == "ON") {
                    Toasty.warning(this, "You have to stop Incognito Mode first").show()
                } else {
                    Dexter.withContext(this)
                            .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            .withListener(this)
                            .check()
                    val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)

                    alertDialogBuilder
                            .setTitle("Clean Directory HideDroid")
                            .setMessage("Would you like delete all files within HideDroid directory?")
                            .setPositiveButton("Yes") { dialog, id ->
                                GlobalScope.launch(Dispatchers.IO) {
                                    FOLDER_FILE!!.deleteRecursively()
                                    prepareFileAndFolders()

                                    val realm = Realm.getDefaultInstance()

                                    realm.use { realm ->
                                        // cancelliamo anche i pacchetti raccolti dalle tabelle EventBuffer e AnalyticsRequest
                                        realm.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.hidedroid.EventBuffer::class.java) }
                                        realm.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.hidedroid.AnalyticsRequest::class.java) }
                                    }

                                    /*realmDebugLogs.use { realmDebugLogs ->
                                        // cancelliamo le informazioni raccolte all'interno delle tabelle Request, Error e Response
                                        realmDebugLogs.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.debug.Request::class.java) }
                                        realmDebugLogs.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.debug.Error::class.java) }
                                        realmDebugLogs.executeTransaction { realm -> realm.delete(com.dave.realmdatahelper.debug.Response::class.java) }
                                    }*/
                                }
                                Toasty.success(application, "Directory Cleaned").show()

                            }
                            .setNegativeButton("No") { dialog, id -> dialog.cancel() }

                    val alertDialog = alertDialogBuilder.create()
                    alertDialog.show()
                }
            }

            R.id.installCACertificate -> {
                if (RootUtil.isDeviceRooted && JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS) && Utilities.isVersionGreaterThanNougat()) {
                    // if device is rooted and certificate is already installed install system ca certificate
                    installSystemCA()
                } else if (!JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS) && Utilities.isVersionGreaterThanNougat()) {
                    // if certificate is not already installed && the version is greater than nougat
                    JKS.install(this, HideDroidApplication.JSK_ALIAS, HideDroidApplication.JSK_ALIAS)
                } else if (JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS) ||
                        !Utilities.isVersionGreaterThanNougat() ||
                        this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE).getBoolean(getString(R.string.key_root_certificate_preference), false)) {
                    // already done everiting
                    Toasty.success(this, getString(R.string.function_install_ca_done)).show()
                } else {
                    // probably error
                    Toasty.error(this, getString(R.string.function_install_ca_error)).show()

                }
            }
            /*R.id.extractDB -> {
                GlobalScope.launch(Dispatchers.IO) {
                    val dataExport = DataExport()
                    dataExport.exportAnalyticsRequest()
                    dataExport.exportRealmDB()
                }
                Toasty.success(this@MainActivity, "Success export realm file on ${Environment.getExternalStorageDirectory()}/HideDroid").show()
            }

            R.id.extractDebugLogs -> {
                if (isDebugEnabled.get()) {
                    val realm = Realm.getInstance((this.application as HideDroidApplication).realmConfigLog)
                    realm.use { realm ->
                        // Information from Request table
                        val allRequest = realm.copyFromRealm(realm.where<com.dave.realmdatahelper.debug.Request>().findAll())
                                as MutableList<com.dave.realmdatahelper.debug.Request>
                        val totalPackets = allRequest.size
                        val numberPacketsTracked = (realm.copyFromRealm(realm.where<com.dave.realmdatahelper.debug.Request>()
                                .equalTo("is_tracked", true).findAll()) as MutableList<com.dave.realmdatahelper.debug.Request>).size
                        val numberPacketsPrivate = (realm.copyFromRealm(realm.where<com.dave.realmdatahelper.debug.Request>()
                                .equalTo("is_private", true).findAll()) as MutableList<com.dave.realmdatahelper.debug.Request>).size

                        val allHostsSet = mutableSetOf<String>()
                        val trackedHostsSet = mutableSetOf<String>()
                        val privateHostsSet = mutableSetOf<String>()
                        for (request in allRequest) {
                            allHostsSet.add(request.host)
                            when {
                                request.is_tracked -> {
                                    trackedHostsSet.add(request.host)
                                }

                                request.is_private -> {
                                    privateHostsSet.add(request.host)
                                }
                            }
                        }
                        val allHosts = allHostsSet.toMutableList()
                        val trackedHosts = trackedHostsSet.toMutableList()
                        val privateHosts = privateHostsSet.toMutableList()

                        // Information from Error table
                        val errorsRealm = realm.copyFromRealm(realm.where<com.dave.realmdatahelper.debug.Error>().findAll())
                                as MutableList<com.dave.realmdatahelper.debug.Error>
                        val errors = mutableListOf<it.unige.hidedroid.log.Error>()
                        for (error in errorsRealm) {
                            errors.add(it.unige.hidedroid.log.Error(error.app, error.host, error.headers, error.body, error.error))
                        }

                        // Information from Response table
                        val acceptedCodes = listOf("200", "201", "203", "204", "206", "207", "226")
                        val responseTable = realm.copyFromRealm(realm.where<Response>().findAll()) as List<Response>
                        var numberAcceptedRequest = 0
                        for (entry in responseTable) {
                            if (entry.code in acceptedCodes) {
                                numberAcceptedRequest++
                            }
                        }
                        val numberRefusedRequest = responseTable.size - numberAcceptedRequest

                        // Create JSON debug file
                        val loggerToJson = LoggerToJson(totalPackets, numberPacketsTracked, numberPacketsPrivate, allHosts,
                                trackedHosts, privateHosts, errors, numberAcceptedRequest, numberRefusedRequest)
                        val debugLogs = Gson().toJson(loggerToJson)
                        DataExport().exportDebugLogs(debugLogs)

                        val dataExport = DataExport()
                        dataExport.exportRealmDebugLogs((this.application as HideDroidApplication).realmConfigLog)
                        dataExport.exportDebugLogsRequestCsv((this.application as HideDroidApplication).realmConfigLog)
                        dataExport.exportDebugLogsErrorCsv((this.application as HideDroidApplication).realmConfigLog)
                        Toasty.success(this, "Debug Logs Extracted successfully").show()
                    }
                } else {
                    Toasty.warning(this, "Enable Debug Mode First!").show()
                }
            }

            R.id.extractDGH -> {
                val dataExport = DataExport()
                dataExport.exportRealmDGH((this.application as HideDroidApplication).realmConfigDGH)
                Toasty.success(this, "DGH Extracted successfully").show()
            }*/
        }
        return super.onOptionsItemSelected(item)
    }

    private fun interceptorFactories(): List<HttpInterceptorFactory> {
        val jsonString = this.assets.open("private_params.json").bufferedReader().use { it.readText() }
        val objectList = Gson().fromJson(jsonString, Array<String>::class.java).asList()
        val interceptorHttp = HttpInterceptor.createFactory(packageManager, this.trackersMappingDomainName, this.appListTracked!!,
                (application as HideDroidApplication).realmConfigPrivateTracker, objectList, isDebugEnabled,
                (this.application as HideDroidApplication).serviceAnonymization!!.dataAnonymizer)
        return listOf(interceptorHttp)
    }

    private fun installSystemCA() {
        // TODO verify that function works
        val isDeviceRooted = RootUtil.isDeviceRooted // if rooted and version is grater than nougat

        if (isDeviceRooted) {
            if (Utilities.isVersionGreaterThanNougat() &&
                    JKS.isInstalledOnDevice(this, HideDroidApplication.JSK_ALIAS)) {
                val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)

                alertDialogBuilder
                        .setTitle("Root Device Detected")
                        .setMessage(getString(R.string.install_system_ca))
                        .setPositiveButton("Yes") { dialog, _ ->
                            if (RootUtil.canRunRootCommands()) {
                                val hashNameSystemCA = HideDroidApplication.getInstance().getJSK().hashSystemCACertificate
                                val pemSystemCA = HideDroidApplication.getInstance().getJSK().pemCACertificate
                                val installedSystemCertificate = RootUtil.installSystemCA(hashNameSystemCA, pemSystemCA)
                                if (installedSystemCertificate) {
                                    Toasty.success(this, "System certificate installed with success").show()
                                    val sharedPref = this.getSharedPreferences(
                                            getString(R.string.preference_file_key), Context.MODE_PRIVATE)
                                    with(sharedPref.edit()) {
                                        putBoolean(getString(R.string.key_root_certificate_preference), true)
                                        commit()
                                    }
                                } else {
                                    Toasty.error(this, "Something went wrong").show()
                                }
                            }
                        }
                        .setNegativeButton("No") { dialog, _ -> dialog.cancel() }

                val alertDialog = alertDialogBuilder.create()
                alertDialog.show()
            } else {
                Toasty.error(this, getString(R.string.function_install_ca_error)).show()
            }
        } else {
            Toasty.error(this, getString(R.string.function_system_ca_string_not_available)).show()
        }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_PREPARE) {
            // ok start vpn
            prepareNetBare(false)
        } else if (resultCode == Activity.RESULT_CANCELED && requestCode == REQUEST_CODE_PREPARE) {
            // code prepare vpn canceled
            myViewModel!!.isClicked = false
        } else if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_CODE_CERTIFICATE_INSTALLED) {
            // certificate user installed with succesfully
            Toasty.success(this, "Certificate user installed successfully").show()
            if (RootUtil.isDeviceRooted)
                installSystemCA()
        }
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
                    LoggerHideDroid.d(TAG, "Delete app tracked ${appTracked.packageName}")
                }
            }

            var appListStatus = UtilitiesStoreDataOnRealmDb.getAllApplicationStatus()
            for (appStatus in appListStatus) {
                var found = false
                for (appInstalled in appList) {
                    if ((ApplicationInfo.FLAG_SYSTEM and appInstalled.flags) == 0 &&
                            appStatus.packageName == appInstalled.packageName) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    // appStatus.delete()
                    appStatus.deleteThisFromRealm()
                    if (BuildConfig.DEBUG) {
                        LoggerHideDroid.d(TAG, "Delete app status ${appStatus.packageName}")
                    }
                }
            }

            appListTracked = UtilitiesStoreDataOnRealmDb.getListPackageNameTracked()
        }
    }

    override fun onPermissionGranted(permissionGrantedResponse: PermissionGrantedResponse) {
        GlobalScope.launch(Dispatchers.IO) {
            prepareFileAndFolders()
        }
    }

    override fun onPermissionDenied(permissionDeniedResponse: PermissionDeniedResponse) {
        if (permissionDeniedResponse.isPermanentlyDenied) {
            // open settings
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", packageName, null)
            intent.data = uri
            startActivity(intent)
        }
    }

    override fun onPermissionRationaleShouldBeShown(permissionRequest: PermissionRequest, permissionToken: PermissionToken) {
        permissionToken.continuePermissionRequest()
    }

    private fun prepareFileAndFolders() {
        LoggerHideDroid.d(TAG, "Prepare File and Folders")
        FOLDER_FILE!!.mkdirs()
        if (!FOLDER_FILE!!.exists()) {
            throw AssertionError("Error creating " + FOLDER_FILE.toString())
        }
    }
}