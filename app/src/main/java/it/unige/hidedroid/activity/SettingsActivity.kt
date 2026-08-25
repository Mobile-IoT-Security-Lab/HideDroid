package it.unige.hidedroid.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.dave.realmdatahelper.debug.Error as DebugError
import com.dave.realmdatahelper.debug.Request as DebugRequest
import com.dave.realmdatahelper.debug.Response as DebugResponse
import com.dave.realmdatahelper.hidedroid.AnalyticsRequest
import com.dave.realmdatahelper.hidedroid.EventBuffer
import com.github.megatronking.netbare.NetBare
import com.github.megatronking.netbare.ssl.JKS
import com.google.gson.Gson
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import com.google.android.material.button.MaterialButton
import es.dmoral.toasty.Toasty
import io.realm.Realm
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.R
import it.unige.hidedroid.log.DataExport
import it.unige.hidedroid.log.LoggerHideDroid
import it.unige.hidedroid.log.LoggerToJson
import it.unige.hidedroid.log.Error as LogError
import it.unige.hidedroid.utils.HideDroidStorage
import it.unige.hidedroid.utils.RootUtil
import it.unige.hidedroid.utils.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug mode, clear data, CA certificate management and data export - moved out
 * of MainActivity's overflow menu into their own screen. Certificate management
 * keeps its own launchers here, independent from MainActivity's certInstallLauncher
 * (which only handles the auto-install-on-VPN-start path).
 */
class SettingsActivity : AppCompatActivity(), PermissionListener {

    companion object {
        private val TAG = SettingsActivity::class.java.name
    }

    private lateinit var certInstallLauncher: ActivityResultLauncher<Intent>
    private lateinit var certExportLauncher: ActivityResultLauncher<Uri?>
    private lateinit var exportDirLauncher: ActivityResultLauncher<Uri?>

    private lateinit var isDebugEnabled: AtomicBoolean
    private lateinit var listener: MainActivity.SharedPreferencesUpdateListener
    private lateinit var buttonDebugMode: MaterialButton
    private lateinit var buttonExportAll: MaterialButton
    private var verifyAfterExport = false
    private var pendingExportCallback: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        isDebugEnabled = (application as HideDroidApplication).isDebugEnabled
        listener = (application as HideDroidApplication).sharedPreferencesUpdateListener

        certInstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toasty.success(this, getString(R.string.ca_install_user_done)).show()
                if (RootUtil.isDeviceRooted && !Utilities.isModernAndroid()) {
                    installSystemCA()
                }
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

        Dexter.withContext(this)
                .withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .withListener(this)
                .check()

        buttonDebugMode = findViewById(R.id.buttonDebugMode)
        buttonExportAll = findViewById(R.id.buttonExportAllData)
        findViewById<MaterialButton>(R.id.buttonClearData).setOnClickListener { handleCleanDirectory() }
        findViewById<MaterialButton>(R.id.buttonInstallCA).setOnClickListener { handleCertificateInstallation() }
        buttonExportAll.setOnClickListener { exportAllData() }

        buttonDebugMode.setOnClickListener {
            val newValue = !isDebugEnabled.get()
            isDebugEnabled.set(newValue)
            listener.onUpdatePreferences(newValue)
            if (newValue) {
                Toasty.success(this, "Debug Mode ON").show()
            } else {
                Toasty.error(this, "Debug Mode OFF").show()
            }
            updateDebugModeUi()
        }

        updateDebugModeUi()
    }

    override fun onResume() {
        super.onResume()
        updateDebugModeUi()
    }

    private fun updateDebugModeUi() {
        val enabled = isDebugEnabled.get()
        buttonDebugMode.isChecked = enabled
        buttonDebugMode.text = getString(R.string.settings_debug_mode) + ": " + if (enabled) "ON" else "OFF"
    }

    /** Single combined export (analytics DB + debug logs + DGH); only available with Debug Mode on. */
    private fun exportAllData() {
        if (!isDebugEnabled.get()) {
            Toasty.warning(this, getString(R.string.settings_export_all_requires_debug)).show()
            return
        }
        runExportWithDir { exportUri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val app = this@SettingsActivity.application as HideDroidApplication
                    val dataExport = DataExport(this@SettingsActivity, exportUri)

                    dataExport.exportAnalyticsRequest()
                    dataExport.exportRealmDB()

                    Realm.getInstance(app.realmConfigLog).use { realm ->
                        val requestClass = DebugRequest::class.java
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

                        val errorClass = DebugError::class.java
                        val errorsRealm = realm.copyFromRealm(realm.where(errorClass).findAll())
                        val errors = mutableListOf<LogError>()
                        for (error in errorsRealm) {
                            errors.add(LogError(error.app, error.host, error.headers, error.body, error.error))
                        }

                        val responseClass = DebugResponse::class.java
                        val acceptedCodes = listOf("200", "201", "203", "204", "206", "207", "226")
                        val responseTable = realm.copyFromRealm(realm.where(responseClass).findAll())
                        val numberAcceptedRequest = responseTable.count { it.code in acceptedCodes }
                        val numberRefusedRequest = responseTable.size - numberAcceptedRequest

                        val loggerToJson = LoggerToJson(totalPackets, numberPacketsTracked, numberPacketsPrivate,
                                allHostsSet.toMutableList(), trackedHostsSet.toMutableList(),
                                privateHostsSet.toMutableList(), errors, numberAcceptedRequest, numberRefusedRequest)
                        val debugLogs = Gson().toJson(loggerToJson)
                        dataExport.exportDebugLogs(debugLogs)

                        dataExport.exportRealmDebugLogs(app.realmConfigLog)
                        dataExport.exportDebugLogsRequestCsv(app.realmConfigLog)
                        dataExport.exportDebugLogsErrorCsv(app.realmConfigLog)
                    }

                    dataExport.exportRealmDGH(app.realmConfigDGH)

                    withContext(Dispatchers.Main) {
                        Toasty.success(this@SettingsActivity, getString(R.string.export_db_done)).show()
                    }
                } catch (e: Exception) {
                    LoggerHideDroid.e(TAG, "exportAllData error: $e")
                    withContext(Dispatchers.Main) {
                        Toasty.error(this@SettingsActivity, "Export failed: ${e.message}").show()
                    }
                }
            }
        }
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
                container.addView(View(this@SettingsActivity).apply {
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
                        Toasty.error(this@SettingsActivity, getString(R.string.ca_force_reinstall_failed)).show()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    Toasty.success(this@SettingsActivity, getString(R.string.ca_force_reinstall_done)).show()
                    if (Utilities.isModernAndroid()) {
                        verifyAfterExport = true
                        openCertificateExportPicker()
                    } else {
                        installSystemCA()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@SettingsActivity, getString(R.string.ca_force_reinstall_failed)).show()
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
                        Toasty.error(this@SettingsActivity, getString(R.string.ca_export_failed)).show()
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
                        Toasty.success(this@SettingsActivity, getString(R.string.ca_force_reinstall_export_done)).show()
                        verifyCertificateInstallation()
                    } else {
                        Toasty.success(this@SettingsActivity, getString(R.string.ca_export_done, fileName)).show()
                    }
                }
            } catch (e: IOException) {
                verifyAfterExport = false
                withContext(Dispatchers.Main) {
                    Toasty.error(this@SettingsActivity, getString(R.string.ca_export_failed)).show()
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
                val userInstalled = JKS.isInstalledOnDevice(this@SettingsActivity, HideDroidApplication.JSK_ALIAS)
                val systemInstalled = isSystemCaInstalled()
                withContext(Dispatchers.Main) {
                    when {
                        systemInstalled && userInstalled -> {
                            Toasty.success(this@SettingsActivity, getString(R.string.ca_status_both)).show()
                        }
                        systemInstalled -> {
                            Toasty.success(this@SettingsActivity, getString(R.string.ca_status_system_only)).show()
                        }
                        userInstalled -> {
                            Toasty.success(this@SettingsActivity, getString(R.string.ca_status_user_only)).show()
                        }
                        else -> {
                            Toasty.warning(this@SettingsActivity, getString(R.string.ca_status_none)).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@SettingsActivity, getString(R.string.ca_export_failed)).show()
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
                        Toasty.error(this@SettingsActivity, "Root access denied").show()
                    }
                    return@launch
                }
                val deleted = RootUtil.deleteSystemCA(nameCert)
                withContext(Dispatchers.Main) {
                    if (deleted) {
                        Toasty.success(this@SettingsActivity, getString(R.string.ca_delete_system_done)).show()
                        val sharedPref = this@SettingsActivity.getSharedPreferences(
                                getString(R.string.preference_file_key), Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            putBoolean(getString(R.string.key_root_certificate_preference), false)
                            commit()
                        }
                    } else {
                        Toasty.error(this@SettingsActivity, getString(R.string.ca_delete_system_failed)).show()
                    }
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
                        Toasty.error(this@SettingsActivity, getString(R.string.ca_delete_user_failed)).show()
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
                        Toasty.success(this@SettingsActivity, getString(R.string.ca_delete_user_done)).show()
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
        if (NetBare.get().isActive) {
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
                        HideDroidStorage.prepareBaseDir(this@SettingsActivity).deleteRecursively()
                        prepareFileAndFolders()

                        val realm = Realm.getDefaultInstance()

                        realm.use { realm ->
                            // cancelliamo anche i pacchetti raccolti dalle tabelle EventBuffer e AnalyticsRequest
                            realm.executeTransaction { realm -> realm.delete(EventBuffer::class.java) }
                            realm.executeTransaction { realm -> realm.delete(AnalyticsRequest::class.java) }
                        }

                        Realm.getInstance((application as HideDroidApplication).realmConfigLog).use { realmDebugLogs ->
                            // cancelliamo le informazioni raccolte all'interno delle tabelle Request, Error e Response
                            realmDebugLogs.executeTransaction { realm -> realm.delete(DebugRequest::class.java) }
                            realmDebugLogs.executeTransaction { realm -> realm.delete(DebugError::class.java) }
                            realmDebugLogs.executeTransaction { realm -> realm.delete(DebugResponse::class.java) }
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
                                    Toasty.error(this@SettingsActivity, "Root access denied").show()
                                }
                                return@launch
                            }
                            val hashNameSystemCA = HideDroidApplication.getInstance().getJSK().hashSystemCACertificate
                            val pemSystemCA = HideDroidApplication.getInstance().getJSK().pemCACertificate
                            val installedSystemCertificate = RootUtil.installSystemCA(hashNameSystemCA, pemSystemCA)
                            withContext(Dispatchers.Main) {
                                if (installedSystemCertificate) {
                                    Toasty.success(this@SettingsActivity, getString(R.string.ca_install_system_done)).show()
                                    val sharedPref = this@SettingsActivity.getSharedPreferences(
                                            getString(R.string.preference_file_key), Context.MODE_PRIVATE)
                                    with(sharedPref.edit()) {
                                        putBoolean(getString(R.string.key_root_certificate_preference), true)
                                        commit()
                                    }
                                } else {
                                    Toasty.error(this@SettingsActivity, getString(R.string.ca_install_system_failed)).show()
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
        } else {
            Toasty.warning(this, "Permission denied.").show()
        }
    }

    override fun onPermissionRationaleShouldBeShown(permission: PermissionRequest?, token: PermissionToken?) {
        token!!.continuePermissionRequest()
    }
}
