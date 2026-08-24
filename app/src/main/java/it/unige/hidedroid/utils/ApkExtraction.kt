package it.unige.hidedroid.utils

import android.content.Context
import java.io.File
import java.io.IOException

/** Base + split APK files copied out of an installed app, ready to repack/resign. */
data class ExtractedApks(val base: File, val splits: List<File>)

/**
 * Copies the base APK (and, for apps installed via an Android App Bundle,
 * every split APK - config/density/ABI/language) of an installed app into a
 * working directory. Splits are never decompiled: they carry no manifest/
 * resources.arsc worth patching, they just need re-signing with the same key
 * as the (decompiled+rebuilt) base APK so the set can be installed together.
 */
object ApkExtraction {

    @Throws(IOException::class)
    fun extractInstalled(context: Context, packageName: String, destDir: File): ExtractedApks {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)

        val baseDst = File(destDir, "$packageName.apk")
        Utilities.copyFile(File(appInfo.publicSourceDir), baseDst)

        val splitDsts = appInfo.splitSourceDirs.orEmpty().map { splitSourcePath ->
            val splitSrc = File(splitSourcePath)
            val splitDst = File(destDir, splitSrc.name)
            Utilities.copyFile(splitSrc, splitDst)
            splitDst
        }

        return ExtractedApks(baseDst, splitDsts)
    }
}
