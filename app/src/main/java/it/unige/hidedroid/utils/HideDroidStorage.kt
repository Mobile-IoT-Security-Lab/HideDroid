package it.unige.hidedroid.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

/**
 * Centralizes HideDroid working directory selection.
 *
 * targetSdkVersion is pinned to 28, so legacy full external-storage access
 * (WRITE_EXTERNAL_STORAGE, no scoped-storage restriction) is available and
 * /sdcard/HideDroid is used directly for everything - general working files,
 * and the repack_app subfolder (see [getRepackWorkDir]) for all repack
 * scratch work and final output, always, with no folder picker.
 */
object HideDroidStorage {

    private const val LEGACY_DIR_NAME = "HideDroid"

    /** /sdcard/HideDroid - general working directory (exports, clear-data). */
    fun getLegacyBaseDir(): File {
        return File(Environment.getExternalStorageDirectory(), LEGACY_DIR_NAME)
    }

    /** Ensures /sdcard/HideDroid exists and returns it. */
    fun prepareBaseDir(context: Context): File {
        val baseDir = getLegacyBaseDir()
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        return baseDir
    }

    /**
     * Canonical, fixed location for ALL repack scratch work (extracted/decompiled/
     * rebuilt/signed APKs) and the final repacked APK -
     * /sdcard/HideDroid/repack_app - always, never user-chosen.
     */
    @JvmStatic
    fun getRepackWorkDir(): File {
        val dir = File(getLegacyBaseDir(), "repack_app")
        dir.mkdirs()
        return dir
    }

    /**
     * Canonical, stable public location for [packageName]'s final repacked/signed
     * base APK - /sdcard/HideDroid/repack_app/<packageName>.apk.
     */
    @JvmStatic
    fun getRepackedApkFile(packageName: String): File {
        return File(getRepackWorkDir(), "$packageName.apk")
    }

    /**
     * Moves [source] (the freshly built+signed apk) into the canonical
     * repacked-apk location for [packageName], replacing any previous build.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun moveToRepackedLocation(packageName: String, source: File): File {
        val destination = getRepackedApkFile(packageName)
        destination.delete()
        if (!source.renameTo(destination)) {
            // Cross-filesystem move (shouldn't normally happen now that everything
            // lives under the same repack_app dir, but stay safe).
            Utilities.copyFile(source, destination)
            source.delete()
        }
        return destination
    }

    // RepackageTask's own scratch files (_merged.apk/_tmp.apk/_signed.apk) always
    // end with one of these; extracted split apks are cleaned up right after use,
    // but if a repack gets killed mid-run any of this can survive in repack_app -
    // exclude it so it can never masquerade as a real repacked app in a listing.
    private val SCRATCH_SUFFIXES = listOf("_merged.apk", "_tmp.apk", "_signed.apk")

    /** Whether [fileName] is a real repacked-apk output (repack_app/<packageName>.apk), not scratch debris. */
    @JvmStatic
    fun isRepackedApkFileName(fileName: String): Boolean {
        if (!fileName.endsWith(".apk")) {
            return false
        }
        return SCRATCH_SUFFIXES.none { fileName.endsWith(it) }
    }
}
