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
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import io.realm.RealmConfiguration
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.animators.Animators
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.models.AppItemRepacking
import it.unige.hidedroid.models.ListAppsItemRepacking
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.view.AppAdapterRepacking
import kotlinx.android.synthetic.main.activity_repacking.*
import me.zhanghai.android.materialprogressbar.MaterialProgressBar
import java.io.File
import java.util.*
import java.util.regex.Pattern


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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG,"onCreate")
        setContentView(R.layout.activity_repacking)
        FOLDER_FILE = File(Environment.getExternalStorageDirectory(), "HideDroid")

        isDebugEnabled = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE).getInt(HideDroidApplication.DEBUG_ENABLED_KEY, -1)

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
            LoadApp().execute()
            swipeApk.isRefreshing = false
        }
    }

    override fun onResume() {
        LoadApp().execute()
        isDebugEnabled = this.getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE).getInt(HideDroidApplication.DEBUG_ENABLED_KEY, -1)
        super.onResume()
    }


    private fun prepareFileAndFolders() {
        LoggerHideDroid.d(TAG, "Prepare File and Folders")
        FOLDER_FILE!!.mkdirs()
        if (!FOLDER_FILE!!.exists()) {
            if (isDebugEnabled == 1) {
                Log.e("errorCreatingFolder", "Error creating $FOLDER_FILE")
            }
            throw AssertionError("Error creating " + FOLDER_FILE.toString())
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

    inner class LoadApp: AsyncTask<Unit, Unit, Unit>() {
        private var apps = mutableListOf<AppItemRepacking>()

        override fun onPreExecute() {
            super.onPreExecute()
            // progress bar circle
            progressBar!!.visibility = View.VISIBLE
            mDeterminateCircularProgressAnimator!!.start();

        }
        override fun doInBackground(vararg params: Unit?) {
            LoggerHideDroid.d(TAG, "Load app")
            applist = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            //var appListTracked = mutableListOf<String>()

            // Get all app installed on device (is slow)
            // var appListTracked = UtilitiesStoreDataOnDb.getListPackageNameTracked()
            val appListTracked = UtilitiesStoreDataOnRealmDb.getListPackageNameTracked()
            LoggerHideDroid.d(TAG, "Finish loading app on db")

            // TODO make faster
            for (app in applist as MutableList<ApplicationInfo>) {
                if ((FLAG_SYSTEM and app.flags) == 0 && app.packageName != packageName) {
                    val isAlreadyTracked = appListTracked.contains(app.packageName)
                    val status = if (!isAlreadyTracked) "Installed" else "Tracked"
                    try {

                        val appItemRepacking = AppItemRepacking(
                                app.packageName,
                                packageManager.getApplicationIcon(app.packageName),
                                app.name,
                                app,
                                status,
                                true,
                                isAlreadyTracked
                        )
                        apps.add(appItemRepacking)
                    } catch (exception: PackageManager.NameNotFoundException) {
                        if (isDebugEnabled == 1) {
                            Log.e("pkNameNotFound", "PackageName not found in list application ${app.packageName}")
                        }
                        Log.d(TAG, "PackageName not found ${app.packageName}")
                    }
                }
            }

            LoggerHideDroid.d(TAG, "Finish loading app installed on device")

            // load all app in HideDroid dir signed
            val pattern = Pattern.compile(".*_signed\\.apk")
            val files = FOLDER_FILE!!.listFiles { pathname -> pattern.matcher(pathname!!.name).matches() }

            // load app already repackaged
            if (files != null) {
                for (fileApp in files) {
                    val packageInfo = packageManager.getPackageArchiveInfo(fileApp.path, 0)
                    if (packageInfo != null && !appListTracked.contains(packageInfo.packageName)) {
                        val app = AppItemRepacking(
                                packageInfo.packageName,
                                packageInfo.applicationInfo.loadIcon(packageManager),
                                packageInfo.applicationInfo.name,
                                packageInfo.applicationInfo,
                                "Not Installed",
                                isInstalled = false,
                                isAlreadyTracked = false
                        )
                        apps.add(app)
                    }
                }
            }

            val stringComparator = Comparator { firstAppItemRepacking: AppItemRepacking, secondAppItemRepacking: AppItemRepacking ->
                firstAppItemRepacking.packageName.compareTo(secondAppItemRepacking.packageName)
            }

            if (apps.sortedWith(stringComparator).isNotEmpty()) {
                apps = apps.sortedWith(stringComparator) as MutableList<AppItemRepacking>
            }

        }

        override fun onPostExecute(result: Unit?) {
            super.onPostExecute(result)
            // Update recycler view
            adapter!!.listAppObjectFiltered = ListAppsItemRepacking(apps)
            adapter!!.listAppObject = ListAppsItemRepacking(apps)
            adapter!!.notifyDataSetChanged()
            mDeterminateCircularProgressAnimator!!.end()
            progressBar!!.visibility = View.GONE

        }

    }

}