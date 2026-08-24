package it.unige.hidedroid.log

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.dave.realmdatahelper.debug.Error
import com.dave.realmdatahelper.debug.Request
import io.realm.Realm
import io.realm.RealmConfiguration
import it.unige.hidedroid.realmdatahelper.UtilitiesStoreDataOnRealmDb
import it.unige.hidedroid.utils.HideDroidStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class DataExport(private val context: Context, private val treeUri: Uri? = null) {

    private val fallbackDir: File by lazy {
        HideDroidStorage.prepareBaseDir(context)
    }

    @Throws(IOException::class)
    fun exportAnalyticsRequest() {
        val analyticsRequests = UtilitiesStoreDataOnRealmDb.getAllAnalyticsRequest()
        writeText("analytics_request.csv") { output ->
            output.bufferedWriter().use { writer ->
                writer.write("id,package_name,host,time,method,path,http_protocol,body_without_special_char\n")
                for (request in analyticsRequests) {
                    writer.write(
                        "${request.id},${escape(request.packageName)},${escape(request.host)},${request.timeStamp}," +
                                "${escape(request.method)},${escape(request.path)},${escape(request.httpProtocol)}," +
                                "${escape(request.bodyWithoutSpecialChar)}\n"
                    )
                }
            }
        }
    }

    @Throws(IOException::class)
    fun exportRealmDB() {
        Realm.getDefaultInstance().use { realm ->
            copyRealmFile(realm.path, "realmeventbuffer.realm")
        }
    }

    @Throws(IOException::class)
    fun exportDebugLogs(debugLogs: String) {
        writeText("debug_logs.json") { output ->
            output.bufferedWriter().use { writer ->
                writer.write(debugLogs)
            }
        }
    }

    @Throws(IOException::class)
    fun exportRealmDebugLogs(realmConfigLog: RealmConfiguration) {
        Realm.getInstance(realmConfigLog).use { realm ->
            copyRealmFile(realm.path, "realmDebugLogs.realm")
        }
    }

    @Throws(IOException::class)
    fun exportDebugLogsRequestCsv(realmConfigLog: RealmConfiguration) {
        Realm.getInstance(realmConfigLog).use { realm ->
            val requests = realm.copyFromRealm(realm.where(Request::class.java).findAll())
            writeText("debug_logs_request.csv") { output ->
                output.bufferedWriter().use { writer ->
                    writer.write("id,package_name,host,method,path,time,is_tracked,is_private,http_protocol,headers,body\n")
                    for (request in requests) {
                        writer.write(
                            "${request.id},${escape(request.packageName)},${escape(request.host)}," +
                                    "${escape(request.method)},${escape(request.path)},${request.timeStamp}," +
                                    "${request.isTracked},${request.isPrivate},${escape(request.httpProtocol)}," +
                                    "${escape(request.headers)},${escape(request.body)}\n"
                        )
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    fun exportDebugLogsErrorCsv(realmConfigLog: RealmConfiguration) {
        Realm.getInstance(realmConfigLog).use { realm ->
            val errors = realm.copyFromRealm(realm.where(Error::class.java).findAll())
            writeText("debug_logs_error.csv") { output ->
                output.bufferedWriter().use { writer ->
                    writer.write("id,app,host,time,headers,body,error\n")
                    for (error in errors) {
                        writer.write(
                            "${error.id},${escape(error.app)},${escape(error.host)},${error.timeStamp}," +
                                    "${escape(error.headers)},${escape(error.body)},${escape(error.error)}\n"
                        )
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    fun exportRealmDGH(realmConfigDGH: RealmConfiguration) {
        Realm.getInstance(realmConfigDGH).use { realm ->
            copyRealmFile(realm.path, "realmDGH.realm")
        }
    }

    @Throws(IOException::class)
    private fun copyRealmFile(realmPath: String, outputName: String) {
        val src = File(realmPath)
        writeBytes(outputName) { output ->
            FileInputStream(src).use { input ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    @Throws(IOException::class)
    private fun writeText(fileName: String, writer: (OutputStream) -> Unit) {
        writeBytes(fileName, writer)
    }

    @Throws(IOException::class)
    private fun writeBytes(fileName: String, writer: (OutputStream) -> Unit) {
        if (treeUri != null) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IOException("Cannot open export directory: $treeUri")
            val existing = dir.findFile(fileName)
            existing?.delete()
            val doc = dir.createFile(mimeType(fileName), fileName)
                    ?: throw IOException("Cannot create $fileName in $treeUri")
            context.contentResolver.openOutputStream(doc.uri)?.use(writer)
                    ?: throw IOException("Cannot open output stream for $fileName")
        } else {
            FileOutputStream(File(fallbackDir, fileName)).use(writer)
        }
    }

    private fun mimeType(fileName: String): String {
        return when {
            fileName.endsWith(".csv") -> "text/csv"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".realm") -> "application/octet-stream"
            else -> "*/*"
        }
    }

    private fun escape(value: String?): String {
        if (value == null) return ""
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }
}
