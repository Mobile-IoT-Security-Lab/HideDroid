package it.unige.hidedroid.utils

import android.app.AlertDialog
import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object Utilities {
    @Throws(IOException::class)
    fun copyFile(src: File?, dst: File) {
        if (!dst.exists()) dst.createNewFile()
        val inFile = FileInputStream(src)
        val out = FileOutputStream(dst)
        val buffer = ByteArray(1024)
        var read: Int
        while (inFile.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
        }
        inFile.close()
        out.flush()
        out.close()
    }

    fun ShowAlertDialog(context: Context?, title: String?, message: String?) {
        val alertDialogBuilder = AlertDialog.Builder(context)
        alertDialogBuilder
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Close") { dialog, id -> dialog.cancel() }
        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    fun isVersionGreaterThanNougat(): Boolean{
        return android.os.Build.VERSION.SDK_INT >=  android.os.Build.VERSION_CODES.N
    }

}