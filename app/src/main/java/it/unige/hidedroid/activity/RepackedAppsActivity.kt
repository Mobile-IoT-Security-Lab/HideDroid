package it.unige.hidedroid.activity

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import apktool.module.SignatureManager
import com.google.android.material.button.MaterialButton
import es.dmoral.toasty.Toasty
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
 *
 * The package name and signing certificate are both read without going through
 * PackageManager#getPackageArchiveInfo: that method runs the archive through the
 * framework's full, split-aware PackageParser, which returns null for any base
 * APK whose manifest requires config splits (isSplitRequired, common on apps
 * built from an Android App Bundle) that aren't sitting next to it - exactly
 * the case for a freshly repacked base APK before its splits are resigned. The
 * package name instead comes from the (self-imposed) <package>.apk file naming
 * convention, and the signing certificate from apksig's own verifier, which
 * parses the zip/signature directly and doesn't care about splits at all.
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
        adapter = RepackedAppsAdapter(
                onInstallClick = { item ->
                    pendingInstallPackageName = item.packageName
                    InstallActions.installSignedApk(this, item.packageName, item.apkFile.absolutePath,
                            TrackingActions.DEFAULT_PRIVACY_LEVEL, uninstallLauncher, installLauncher)
                },
                onDeleteClick = { item -> confirmDelete(item) }
        )
        recyclerView.adapter = adapter
    }

    private fun confirmDelete(item: RepackedApkItem) {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.action_delete_repacked_short))
                .setMessage(getString(R.string.delete_repacked_confirm, item.packageName, item.apkFile.path))
                .setPositiveButton("Yes") { _, _ ->
                    item.apkFile.delete()
                    Toasty.info(this, getString(R.string.delete_repacked_done)).show()
                    reload()
                }
                .setNegativeButton("No", null)
                .show()
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
        val packageName = apkFile.nameWithoutExtension
        val archiveCert = try {
            SignatureManager.getSignerCertificate(apkFile)
        } catch (e: Exception) {
            null
        }
        if (isAlreadyInstalledWithMatchingSignature(packageName, archiveCert)) {
            return null
        }
        val icon = try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            packageManager.defaultActivityIcon
        }
        return RepackedApkItem(packageName, icon, apkFile)
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

private class RepackedAppsAdapter(
        private val onInstallClick: (RepackedApkItem) -> Unit,
        private val onDeleteClick: (RepackedApkItem) -> Unit
) : RecyclerView.Adapter<RepackedAppsAdapter.ViewHolder>() {

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
        holder.deleteButton.setOnClickListener { onDeleteClick(item) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.iconRepackedApp)
        val packageName: TextView = itemView.findViewById(R.id.packageNameRepackedApp)
        val path: TextView = itemView.findViewById(R.id.pathRepackedApp)
        val installButton: MaterialButton = itemView.findViewById(R.id.buttonInstallRepackedApp)
        val deleteButton: ImageButton = itemView.findViewById(R.id.buttonDeleteRepackedApp)
    }
}
