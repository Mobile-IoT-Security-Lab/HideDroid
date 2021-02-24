package it.unige.hidedroid.realmdatahelper

import android.os.Environment
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.kotlin.where
import it.unige.hidedroid.log.LoggerHideDroid
import java.io.*
import java.util.regex.Matcher
import java.util.regex.Pattern


class DataExport {

    companion object{
        val TAG = DataExport::class.java.name
        val FOLDER_FILE = "${Environment.getExternalStorageDirectory()}/HideDroid"
    }
    fun exportRealmDB(){
        val realm = Realm.getDefaultInstance()
        try {
            val file = File(Environment.getExternalStorageDirectory(), "HideDroid/events.realm")
            if (file.exists()) {
                file.delete()
            }
            realm.writeCopyTo(file)
        } catch (e: IOException) {
            realm.close()
            e.printStackTrace()
        }
    }

    fun exportRealmDebugLogs(realmConfigLog: RealmConfiguration){
        val realm = Realm.getInstance(realmConfigLog)
        try {
            val file = File(Environment.getExternalStorageDirectory(), "HideDroid/debug_logs.realm")
            if (file.exists()) {
                file.delete()
            }
            realm.writeCopyTo(file)
        } catch (e: IOException) {
            realm.close()
            e.printStackTrace()
        }
    }

    fun exportRealmDGH(realmConfigDGH: RealmConfiguration) {
        val realm = Realm.getInstance(realmConfigDGH)
        try {
            val file = File(Environment.getExternalStorageDirectory(), "HideDroid/DGH.realm")
            if (file.exists()) {
                file.delete()
            }
            realm.writeCopyTo(file)
        } catch (e: IOException) {
            realm.close()
            e.printStackTrace()
        }
    }

    fun exportDebugLogs(log: String) {
        val file = File(Environment.getExternalStorageDirectory(), "HideDroid/debug_logs.json")
        if (file.exists()) {
            file.delete()
        }
        val fileOutputStream = FileOutputStream(file)
        fileOutputStream.use { fileOutputStream ->
            fileOutputStream.write(log.toByteArray())
        }
    }

    fun exportDebugLogsRequestCsv(realmConfigLog: RealmConfiguration) {
        val realm = Realm.getInstance(realmConfigLog)
        realm.use { realm ->
            val realmResults = realm.where<com.dave.realmdatahelper.debug.Request>().findAll()
            var dataP: String? = null
            val header = "host;; body;; is_tracked;; is_private"
            saveBackup(header, "Request.csv")
            for (request in realmResults) {
                dataP = request.toString()
                saveBackup(dataP, "Request.csv")
            }
        }
    }

    fun exportDebugLogsErrorCsv(realmConfigLog: RealmConfiguration) {
        val realm = Realm.getInstance(realmConfigLog)
        realm.use { realm ->
            val realmResults = realm.where<com.dave.realmdatahelper.debug.Error>().findAll()
            var dataP: String? = null
            val header = "app;; host;; headers;; body;; error"
            saveBackup(header, "Error.csv")
            for (error in realmResults) {
                dataP = error.toString()
                saveBackup(dataP, "Error.csv")
            }
        }
    }

    fun exportAnalyticsRequest() {
        val realm = Realm.getDefaultInstance()
        realm.use { realm ->
            val realmResults = realm.where<com.dave.realmdatahelper.hidedroid.AnalyticsRequest>().findAll()
            var dataP: String? = null
            val header = "id;; packageName;; host;; timeStamp;; headersJson;; bodyOffset;; bodyWithoutSpecialChar"
            saveBackup(header, "AnalyticsRequest.csv")
            for (analyticsRequest in realmResults) {
                dataP = analyticsRequest.toString()
                saveBackup(dataP, "AnalyticsRequest.csv")
            }
        }
    }

    fun grabHeader(realm:Realm, model:String ):String{
        val schema = realm.schema
        val testSchema = schema[model]
        val header = testSchema!!.fieldNames.toString()

        var dataProcessed = String()
        val p: Pattern = Pattern.compile("\\[(.*?)\\]")
        val m: Matcher = p.matcher(header)

        while (m.find()) {
            dataProcessed += m.group(1).trim().replace("\\p{Z}", "")
        }

        return dataProcessed
    }

    fun saveBackup(data:String, file_name:String){
        var fos: FileOutputStream? = null

        try {
            fos = FileOutputStream("$FOLDER_FILE/$file_name", true)
            fos.write(data.toByteArray())
            fos.write("\n".toByteArray())
            LoggerHideDroid.d(TAG, "saved to: $FOLDER_FILE/$file_name")
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            if (fos != null) {
                try {
                    fos.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
}