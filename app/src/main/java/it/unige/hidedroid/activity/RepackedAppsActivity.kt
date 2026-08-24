package it.unige.hidedroid.activity

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import it.unige.hidedroid.R
import it.unige.hidedroid.utils.HideDroidStorage
import it.unige.hidedroid.utils.InstallActions
import it.unige.hidedroid.utils.TrackingActions
import java.io.File

private data class RepackedApkItem(val packageName: String, val icon: Drawable, val apkFile: File)

/**
 * Dedicated screen listing every repacked APK sitting in the canonical
 * /sdcard/HideDroid/repack_app/<package>.apk location that isn't already
 * installed, for manual installation independent of the main app list.
 *
 * "Not installed" is decided by comparing signing certificates (the apk
 * file's vs. the currently-installed package's), not our own ApplicationStatus
 * bookkeeping - that flag's meaning differs between the "app already trusts
 * user certs, skip rebuild" fast path and the real repack-and-install path,
 * so it isn't a reliable signal for "has *this* file been installed".
 */
class RepackedAppsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: RepackedAppsAdapter

    private lateinit var uninstallLauncher: ActivityResultLauncher<Intent>
    private lateinit var installLauncher: ActivityResultLauncher<Intent>
    private var pendingInstallPackageName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repacked_apps)
        title = getString(R.string.repacked_apps_title)

        uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            InstallActions.onUninstallResult(this, result.resultCode, installLauncher)
        }
        installLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingInstallPackageName?.let { InstallActions.onInstallResult(this, result.resultCode, it, TrackingActions.DEFAULT_PRIVACY_LEVEL) }
            reload()
        }

        recyclerView = findViewById(R.id.recyclerViewRepackedApps)
        emptyView = findViewById(R.id.emptyRepackedApps)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = RepackedAppsAdapter { item ->
            pendingInstallPackageName = item.packageName
            InstallActions.installSignedApk(this, item.packageName, item.apkFile.absolutePath,
                    TrackingActions.DEFAULT_PRIVACY_LEVEL, uninstallLauncher, installLauncher)
        }
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val repackDir = File(HideDroidStorage.getLegacyBaseDir(), "repack_app")
        val items = repackDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
                ?.mapNotNull { apkFile -> toItem(apkFile) }
                ?.sortedBy { it.packageName }
                ?: emptyList()

        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toItem(apkFile: File): RepackedApkItem? {
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageArchiveInfo(apkFile.path, signingFlags) ?: return null

        if (isAlreadyInstalledWithMatchingSignature(packageInfo.packageName, signingCertificateOf(packageInfo))) {
            return null
        }
        val icon = packageInfo.applicationInfo.loadIcon(packageManager)
        return RepackedApkItem(packageInfo.packageName, icon, apkFile)
    }

    private fun isAlreadyInstalledWithMatchingSignature(packageName: String, archiveCert: ByteArray?): Boolean {
        val signingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installedInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(signingFlags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, signingFlags)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val installedCert = signingCertificateOf(installedInfo)
        return archiveCert != null && installedCert != null && archiveCert.contentEquals(installedCert)
    }

    private fun signingCertificateOf(packageInfo: PackageInfo): ByteArray? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        }
    }
}

private class RepackedAppsAdapter(private val onInstallClick: (RepackedApkItem) -> Unit) :
        RecyclerView.Adapter<RepackedAppsAdapter.ViewHolder>() {

    private var items: List<RepackedApkItem> = emptyList()

    fun submit(newItems: List<RepackedApkItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_repacked_app, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.packageName.text = item.packageName
        holder.path.text = item.apkFile.path
        holder.installButton.setOnClickListener { onInstallClick(item) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.iconRepackedApp)
        val packageName: TextView = itemView.findViewById(R.id.packageNameRepackedApp)
        val path: TextView = itemView.findViewById(R.id.pathRepackedApp)
        val installButton: MaterialButton = itemView.findViewById(R.id.buttonInstallRepackedApp)
    }
}
