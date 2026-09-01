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
import android.provider.Settings
import android.transition.Explode
import com.dave.anonymization_data.anonymizationthreads.DataAnonymizer
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import it.unige.hidedroid.BuildConfig
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.interceptor.HttpInterceptor
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.realmdatahelper.*
import it.unige.hidedroid.service.ServiceAnonymization
import it.unige.hidedroid.utils.DomainAnonymizationHelper
import it.unige.hidedroid.utils.HideDroidStorage
import it.unige.hidedroid.viewmodels.StatusVPNViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), NetBareListener, PermissionListener {

    companion object {
        private val TAG = MainActivity::class.java.name
    }

    private lateinit var vpnPrepLauncher: ActivityResultLauncher<Intent>
    private lateinit var certInstallLauncher: ActivityResultLauncher<Intent>

    private var myViewModel: StatusVPNViewModel? = null
    private var appListTracked: MutableList<String>? = null
    private lateinit var mNetBare: NetBare
    private lateinit var trackersMappingDomainName: TrackersMappingDomainName
    private lateinit var isDebugEnabled: AtomicBoolean
    private lateinit var listener: SharedPreferencesUpdateListener
    private lateinit var handle_vpn_button: android.widget.Button
    private lateinit var addAppButton: FloatingActionButton

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
                // Further certificate management (system CA, export, verify, force
                // reinstall) now all lives in SettingsActivity, discoverable there.
                updateVpnButtonState()
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

        // get view model
        myViewModel = ViewModelProvider(this).get(StatusVPNViewModel::class.java)

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

        if (!this::trackersMappingDomainName.isInitialized || this.appListTracked == null) {
            // Trackers mapping / app list are loaded in onResume on a background
            // dispatcher; starting the VPN before that would crash on the
            // lateinit property or monitor zero apps.
            Toasty.warning(application, getString(R.string.vpn_not_ready)).show()
            return
        }

        val dataAnonymizer = (application as HideDroidApplication).serviceAnonymization?.dataAnonymizer
        if (dataAnonymizer == null) {
            Toasty.warning(application, getString(R.string.vpn_not_ready)).show()
            return
        }

        var config = NetBareConfig.defaultHttpConfigWithConnectivityManager(
                HideDroidApplication.getInstance().getJSK(),
                interceptorFactories(dataAnonymizer),
                HideDroidApplication.getInstance().getSystemService(Context.CONNECTIVITY_SERVICE)
                        as ConnectivityManager?,
                this.appListTracked
        )

        myViewModel!!.isClicked = true
        mNetBare.start(config)
    }

    override fun onResume() {
        updateVpnButtonState()

        lifecycleScope.launch(Dispatchers.IO) {
            updateDBPackageNamePrivacyLevel()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            trackersMappingDomainName = TrackersMappingDomainName(HashMap())
            trackersMappingDomainName.populateMapping("trackers.json", this@MainActivity)
            DomainAnonymizationHelper.seedDefaultsIfEmpty(this@MainActivity)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {

            R.id.action_select_apps -> {
                startActivity(Intent(this, SelectAppActivity::class.java))
            }

            R.id.action_show_repacked_apps -> {
                startActivity(Intent(this, RepackedAppsActivity::class.java))
            }

            R.id.action_domain_settings -> {
                startActivity(Intent(this, DomainSettingsActivity::class.java))
            }

            R.id.action_live_traffic -> {
                startActivity(Intent(this, TrafficActivity::class.java))
            }

            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun interceptorFactories(dataAnonymizer: DataAnonymizer): List<HttpInterceptorFactory> {
        val jsonString = this.assets.open("private_params.json").bufferedReader().use { it.readText() }
        val objectList = Gson().fromJson(jsonString, Array<String>::class.java).asList()
        val interceptorHttp = HttpInterceptor.createFactory(packageManager, this.trackersMappingDomainName, this.appListTracked!!,
                (application as HideDroidApplication).realmConfigPrivateTracker,
                (application as HideDroidApplication).realmConfigLog, objectList, isDebugEnabled,
                (application as HideDroidApplication).isDomainBlockingEnabled,
                dataAnonymizer)
        return listOf(interceptorHttp)
    }

    private suspend fun updateDBPackageNamePrivacyLevel() {
        // This dispatcher is optimized to perform disk or network I/O outside of the main thread
        // this function update app installed or removed by the user to update the db
        withContext(Dispatchers.IO) {
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
            // Reload the tracked list only after pruning uninstalled apps, so the
            // VPN allowlist and the interceptor's monitoring list stay in sync.
            appListTracked = UtilitiesStoreDataOnRealmDb.getListPackageNameTracked()
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