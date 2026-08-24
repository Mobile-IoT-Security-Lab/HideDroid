package it.unige.hidedroid.activity

import android.Manifest
import android.animation.ValueAnimator
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.animators.Animators
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.models.AppItemRepacking
import it.unige.hidedroid.models.ListAppsItemRepacking
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.utils.HideDroidStorage
import it.unige.hidedroid.utils.InstallActions
import it.unige.hidedroid.utils.TrackingActions
import it.unige.hidedroid.view.AppAdapterRepacking
import it.unige.hidedroid.view.TrackedFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import java.io.File


class SelectAppActivity() : AppCompatActivity(), PermissionListener {

    companion object {
        private val TAG = SelectAppActivity::class.java.name
    }

    private var applist: MutableList<ApplicationInfo>? = null
    private var adapter: AppAdapterRepacking? = null
    private var searchView: SearchView? = null
    private var mDeterminateCircularProgressAnimator: ValueAnimator? = null
    private var progressBar: MaterialProgressBar?= null
    private var isDebugEnabled = -1

    private var FOLDER_FILE: File? = null

    private lateinit var recyclerViewListApps: androidx.recyclerview.widget.RecyclerView
    private lateinit var swipeApk: androidx.swiperefreshlayout.widget.SwipeRefreshLayout

    lateinit var uninstallLauncher: ActivityResultLauncher<Intent>
    lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var pendingInstallPackageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG,"onCreate")

        uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            InstallActions.onUninstallResult(this, result.resultCode, installLauncher)
        }
        installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingInstallPackageName?.let { InstallActions.onInstallResult(this, result.resultCode, it, TrackingActions.DEFAULT_PRIVACY_LEVEL) }
            refreshApps()
        }
        setContentView(R.layout.activity_repacking)
        FOLDER_FILE = HideDroidStorage.prepareBaseDir(this)

        isDebugEnabled = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE).getInt(HideDroidApplication.DEBUG_ENABLED_KEY, -1)

        recyclerViewListApps = findViewById(R.id.recyclerViewListApps)
        swipeApk = findViewById(R.id.swipeApk)

        // check permission
        Dexter.withContext(this)
                .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .withListener(this)
                .check()

        val linearLayoutManager =
                LinearLayoutManager(baseContext, LinearLayoutManager.VERTICAL, false)
        recyclerViewListApps.layoutManager = linearLayoutManager

        progressBar = findViewById<MaterialProgressBar>(R.id.progress_bar)
        mDeterminateCircularProgressAnimator = Animators.makeDeterminateCircularPrimaryProgressAnimator(mutableListOf(progressBar!!))

        adapter = AppAdapterRepacking(ListAppsItemRepacking(mutableListOf<AppItemRepacking>()), ListAppsItemRepacking(mutableListOf<AppItemRepacking>()),baseContext, this)
        recyclerViewListApps.adapter = adapter
        adapter!!.notifyDataSetChanged()

        // swipe to refresh
        swipeApk.setOnRefreshListener {
            refreshApps()
            swipeApk.isRefreshing = false
        }

        setupTrackedFilterButtons()
    }

    private fun setupTrackedFilterButtons() {
        val filterAll = findViewById<View>(R.id.filterAll)
        val filterTracked = findViewById<View>(R.id.filterTracked)
        val filterNotTracked = findViewById<View>(R.id.filterNotTracked)

        val buttons = listOf(filterAll, filterTracked, filterNotTracked)
        fun select(selected: View, mode: TrackedFilter) {
            buttons.forEach { it.isEnabled = it !== selected }
            adapter?.setTrackedFilter(mode)
        }

        filterAll.setOnClickListener { select(filterAll, TrackedFilter.ALL) }
        filterTracked.setOnClickListener { select(filterTracked, TrackedFilter.TRACKED) }
        filterNotTracked.setOnClickListener { select(filterNotTracked, TrackedFilter.NOT_TRACKED) }
        filterAll.isEnabled = false
    }

    /** Reloads the list; called after a quick track/repack action changes app state. */
    fun refreshApps() {
        lifecycleScope.launch { loadApps() }
    }

    /** Manually installs a signed apk already sitting in the output folder (uninstalling the old build first if needed). */
    fun installRepackedApk(packageName: String, signedApkPath: String) {
        pendingInstallPackageName = packageName
        InstallActions.installSignedApk(this, packageName, signedApkPath,
                TrackingActions.DEFAULT_PRIVACY_LEVEL, uninstallLauncher, installLauncher)
    }

    /** Called after a quick "Repack" action finishes; offers to install the resigned APK (uninstalling the old build first if needed). */
    fun promptInstallRepacked(packageName: String, signedApkPath: String) {
        if (!File(signedApkPath).isFile) {
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.repack_finished_title))
                .setMessage(getString(R.string.repack_finished_install_prompt, packageName))
                .setPositiveButton("Yes") { _, _ ->
                    installRepackedApk(packageName, signedApkPath)
                }
                .setNegativeButton("No") { dialog, _ -> dialog.cancel() }
                .show()
    }

    override fun onResume() {
        refreshApps()
        isDebugEnabled = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE).getInt(HideDroidApplication.DEBUG_ENABLED_KEY, -1)
        super.onResume()
    }


    private fun prepareFileAndFolders(): Boolean {
        LoggerHideDroid.d(TAG, "Prepare File and Folders")
        FOLDER_FILE!!.mkdirs()
        return if (!FOLDER_FILE!!.exists()) {
            if (isDebugEnabled == 1) {
                Log.e("errorCreatingFolder", "Error creating $FOLDER_FILE")
            }
            false
        } else {
            true
        }
    }

    override fun onPermissionGranted(permissionGrantedResponse: PermissionGrantedResponse) {
        prepareFileAndFolders()
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_search, menu)

        // Associate searchable configuration with the SearchView
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchView = menu!!.findItem(R.id.action_search)
                .actionView as SearchView
        searchView!!.setSearchableInfo(
                searchManager
                        .getSearchableInfo(componentName)
        )
        searchView!!.maxWidth = Int.MAX_VALUE


        // listening to search query text change
        searchView!!.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                // filter recycler view when query submitted
                adapter!!.filter.filter(query)
                return false
            }

            override fun onQueryTextChange(query: String?): Boolean {
                // filter recycler view when text is changed
                adapter!!.filter.filter(query)
                return false
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_show_repacked_apps) {
            startActivity(Intent(this, RepackedAppsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private suspend fun loadApps() {
        // UI setup on Main dispatcher
        progressBar!!.visibility = View.VISIBLE
        mDeterminateCircularProgressAnimator!!.start()

        val apps = withContext(Dispatchers.IO) {
            val result = mutableListOf<AppItemRepacking>()
            LoggerHideDroid.d(TAG, "Load app")

            val appListTracked = UtilitiesStoreDataOnRealmDb.getListPackageNameTracked()
            val statusByPackageName = UtilitiesStoreDataOnRealmDb.getAllApplicationStatus()
                    .associateBy { it.packageName }
            LoggerHideDroid.d(TAG, "Finish loading app on db")

            val installedApps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getInstalledApplications(
                        PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            val installedPackageNames = installedApps.map { it.packageName }.toSet()

            for (app in installedApps) {
                if ((FLAG_SYSTEM and app.flags) == 0 && app.packageName != packageName) {
                    val isAlreadyTracked = appListTracked.contains(app.packageName)
                    val status = if (!isAlreadyTracked) "Installed" else "Tracked"
                    try {
                        val appItemRepacking = AppItemRepacking(
                                app.packageName,
                                app.loadIcon(packageManager),
                                app.name,
                                app,
                                status,
                                true,
                                isAlreadyTracked,
                                if (isAlreadyTracked) statusByPackageName[app.packageName]?.isRepackaged else null
                        )
                        result.add(appItemRepacking)
                    } catch (exception: PackageManager.NameNotFoundException) {
                        if (isDebugEnabled == 1) {
                            Log.e("pkNameNotFound", "PackageName not found in list application ${app.packageName}")
                        }
                        Log.d(TAG, "PackageName not found ${app.packageName}")
                    }
                }
            }

            LoggerHideDroid.d(TAG, "Finish loading app installed on device")

            // Every repacked APK in the canonical /sdcard/HideDroid/repack_app/<pkg>.apk
            // location that isn't currently installed - shown regardless of tracked state
            // so the user can find and install any of them manually (packages already
            // installed are covered by the loop above instead).
            val repackedApkFiles = File(HideDroidStorage.getLegacyBaseDir(), "repack_app")
                    .listFiles { f -> f.isFile && f.name.endsWith(".apk") }

            if (repackedApkFiles != null) {
                for (fileApp in repackedApkFiles) {
                    @Suppress("DEPRECATION")
                    val packageInfo = packageManager.getPackageArchiveInfo(fileApp.path, 0)
                    if (packageInfo != null && !installedPackageNames.contains(packageInfo.packageName)) {
                        val app = AppItemRepacking(
                                packageInfo.packageName,
                                packageInfo.applicationInfo.loadIcon(packageManager),
                                packageInfo.applicationInfo.name,
                                packageInfo.applicationInfo,
                                "Not Installed",
                                isInstalled = false,
                                isAlreadyTracked = appListTracked.contains(packageInfo.packageName),
                                signedApkPath = fileApp.absolutePath
                        )
                        result.add(app)
                    }
                }
            }

            val stringComparator = Comparator { firstAppItemRepacking: AppItemRepacking, secondAppItemRepacking: AppItemRepacking ->
                firstAppItemRepacking.packageName.compareTo(secondAppItemRepacking.packageName)
            }

            if (result.sortedWith(stringComparator).isNotEmpty()) {
                result.sortedWith(stringComparator)
            } else {
                result
            }
        }

        // UI update back on Main dispatcher
        adapter!!.listAppObjectFiltered = ListAppsItemRepacking(apps.toMutableList())
        adapter!!.listAppObject = ListAppsItemRepacking(apps.toMutableList())
        adapter!!.notifyDataSetChanged()
        mDeterminateCircularProgressAnimator!!.end()
        progressBar!!.visibility = View.GONE
    }

}
