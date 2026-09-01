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
import android.widget.ScrollView
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
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private data class RepackedApkItem(
    val packageName: String,
    val icon: Drawable,
    val apkFile: File,
    val installed: Boolean
)

/**
 * Dedicated screen listing every repacked APK sitting in the canonical
 * /sdcard/HideDroid/repack_app/<package>.apk location, whether or not a
 * matching build is currently installed (installed ones are marked, and their
 * content stays viewable).
 *
 * "Installed" is decided by comparing signing certificates (the apk
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

    companion object {
        // ponytail: hard cap instead of streaming/paging; raise if previews ever need big files
        private const val MAX_PREVIEW_BYTES = 256 * 1024
    }

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
                onContentClick = { item -> showApkContent(item) },
                onDeleteClick = { item -> confirmDelete(item) }
        )
        recyclerView.adapter = adapter
    }

    /**
     * Browses the APK as a plain zip: entry list first, then a preview of the
     * selected entry if it is mostly printable text. Binary entries
     * (AndroidManifest.xml AXML, resources.arsc, classes.dex, ...) are only
     * reported as binary - no decompilation here.
     */
    private fun showApkContent(item: RepackedApkItem) {
        val entries = try {
            ZipFile(item.apkFile).use { zip -> zip.entries().toList().sortedBy { it.name } }
        } catch (e: Exception) {
            Toasty.error(this, getString(R.string.repacked_content_read_failed, e.message)).show()
            return
        }
        val labels = entries.map { "${it.name}  (${it.size} B)" }.toTypedArray()
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.repacked_content_title, item.packageName))
                .setItems(labels) { _, which -> showZipEntry(item.apkFile, entries[which]) }
                .setNegativeButton("Close", null)
                .show()
    }

    private fun showZipEntry(apkFile: File, entry: ZipEntry) {
        if (entry.size > MAX_PREVIEW_BYTES) {
            Toasty.info(this, getString(R.string.repacked_content_too_large, entry.name)).show()
            return
        }
        val bytes = try {
            ZipFile(apkFile).use { zip ->
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (e: Exception) {
            Toasty.error(this, getString(R.string.repacked_content_read_failed, e.message)).show()
            return
        }
        if (!isMostlyText(bytes)) {
            Toasty.info(this, getString(R.string.repacked_content_binary, entry.name)).show()
            return
        }
        val textView = TextView(this).apply {
            text = String(bytes, StandardCharsets.UTF_8)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply {
            addView(textView)
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(entry.name)
                .setView(scrollView)
                .setPositiveButton("Close", null)
                .show()
    }

    private fun isMostlyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return true
        }
        val sample = if (bytes.size > 4096) bytes.copyOfRange(0, 4096) else bytes
        val printable = sample.count { b ->
            val v = b.toInt() and 0xFF
            v in 0x20..0x7E || v == '\n'.code || v == '\r'.code || v == '\t'.code
        }
        return printable.toFloat() / sample.size > 0.85f
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
        val repackDir = HideDroidStorage.getRepackWorkDir()
        val items = repackDir.listFiles { f -> f.isFile && HideDroidStorage.isRepackedApkFileName(f.name) }
                ?.map { apkFile -> toItem(apkFile) }
                ?.sortedBy { it.packageName }
                ?: emptyList()

        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toItem(apkFile: File): RepackedApkItem {
        val packageName = apkFile.nameWithoutExtension
        val archiveCert = try {
            SignatureManager.getSignerCertificate(apkFile)
        } catch (e: Exception) {
            null
        }
        val installed = isAlreadyInstalledWithMatchingSignature(packageName, archiveCert)
        val icon = try {
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            packageManager.defaultActivityIcon
        }
        return RepackedApkItem(packageName, icon, apkFile, installed)
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
        private val onContentClick: (RepackedApkItem) -> Unit,
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
        val context = holder.itemView.context
        holder.icon.setImageDrawable(item.icon)
        holder.packageName.text = item.packageName
        holder.path.text = item.apkFile.path + "  -  " +
                context.getString(if (item.installed) R.string.repacked_status_installed else R.string.repacked_status_not_installed)
        holder.installButton.setOnClickListener { onInstallClick(item) }
        holder.contentButton.setOnClickListener { onContentClick(item) }
        holder.deleteButton.setOnClickListener { onDeleteClick(item) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.iconRepackedApp)
        val packageName: TextView = itemView.findViewById(R.id.packageNameRepackedApp)
        val path: TextView = itemView.findViewById(R.id.pathRepackedApp)
        val contentButton: MaterialButton = itemView.findViewById(R.id.buttonViewContentRepackedApp)
        val installButton: MaterialButton = itemView.findViewById(R.id.buttonInstallRepackedApp)
        val deleteButton: ImageButton = itemView.findViewById(R.id.buttonDeleteRepackedApp)
    }
}
