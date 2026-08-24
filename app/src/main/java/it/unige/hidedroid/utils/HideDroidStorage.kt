package it.unige.hidedroid.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import it.unige.hidedroid.log.LoggerHideDroid
import java.io.File
import java.io.IOException

/**
 * Centralizes HideDroid working directory selection.
 *
 * targetSdkVersion is pinned to 28, so legacy full external-storage access
 * (WRITE_EXTERNAL_STORAGE, no scoped-storage restriction) is available and
 * /sdcard/HideDroid is used directly - same shared location the final
 * repacked APK already lands in (see [getRepackedApkFile]), so a repack's
 * scratch decode/build output and its result sit together by default.
 */
object HideDroidStorage {

    private const val LEGACY_DIR_NAME = "HideDroid"
    private const val TAG = "HideDroidStorage"
    private const val PREF_OUTPUT_FOLDER = "output_folder_path"

    /**
     * Preferred working directory: the user-chosen output folder (see
     * [setOutputFolder]) if one was set, otherwise /sdcard/HideDroid.
     * Always returns a non-null File; callers must verify existence.
     */
    fun getBaseDir(context: Context): File {
        val customPath = prefs(context).getString(PREF_OUTPUT_FOLDER, null)
        if (customPath != null) {
            val custom = File(customPath)
            if (custom.isDirectory || custom.mkdirs()) {
                return custom
            }
        }
        return getLegacyBaseDir()
    }

    /** Whether the user has picked an output folder via [FolderPicker], as opposed to the default. */
    fun hasCustomOutputFolder(context: Context): Boolean =
            prefs(context).contains(PREF_OUTPUT_FOLDER)

    fun setOutputFolder(context: Context, dir: File) {
        prefs(context).edit().putString(PREF_OUTPUT_FOLDER, dir.absolutePath).apply()
    }

    private fun prefs(context: Context) =
            context.getSharedPreferences(context.getString(it.unige.hidedroid.R.string.preference_file_key), Context.MODE_PRIVATE)

    /**
     * Legacy /sdcard/HideDroid directory. Used for migration, and as the root
     * for each package's final repacked APK (see [getRepackedApkFile]).
     */
    fun getLegacyBaseDir(): File {
        return File(Environment.getExternalStorageDirectory(), LEGACY_DIR_NAME)
    }

    /**
     * Canonical, stable public location for [packageName]'s final repacked/signed
     * base APK - /sdcard/HideDroid/repack_app/<packageName>.apk - independent of
     * whatever output folder was used for the scratch decode/build work.
     */
    @JvmStatic
    fun getRepackedApkFile(packageName: String): File {
        val dir = File(getLegacyBaseDir(), "repack_app")
        dir.mkdirs()
        return File(dir, "$packageName.apk")
    }

    /**
     * Canonical location for one of [packageName]'s resigned split APKs (config/density/
     * ABI/language splits from an Android App Bundle install) - these are only re-signed
     * with the same key as the base APK, never decompiled/rebuilt, and must keep their
     * original split file name since the installer session matches splits by name.
     */
    @JvmStatic
    fun getRepackedSplitFile(packageName: String, splitFileName: String): File {
        val dir = File(getLegacyBaseDir(), "repack_app/${packageName}_splits")
        dir.mkdirs()
        return File(dir, splitFileName)
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
            // Cross-filesystem move (e.g. source sits on a user-chosen SD card folder).
            Utilities.copyFile(source, destination)
            source.delete()
        }
        return destination
    }

    /**
     * Ensure the base directory exists, migrating any files from the legacy
     * /sdcard/HideDroid location the first time the new directory is created.
     */
    fun prepareBaseDir(context: Context): File {
        val baseDir = getBaseDir(context)
        if (!baseDir.exists()) {
            baseDir.mkdirs()
            if (baseDir.exists()) {
                migrateFromLegacy(baseDir)
            }
        }
        return baseDir
    }

    private fun migrateFromLegacy(destination: File) {
        val legacy = getLegacyBaseDir()
        if (!legacy.exists() || !legacy.isDirectory) return

        val files = legacy.listFiles { f -> f != null && f.isFile } ?: return
        var migrated = 0
        for (file in files) {
            try {
                file.copyTo(File(destination, file.name), overwrite = true)
                migrated++
            } catch (e: IOException) {
                LoggerHideDroid.d(TAG, "Failed to migrate ${file.name}: $e")
            }
        }
        if (migrated > 0) {
            LoggerHideDroid.d(TAG, "Migrated $migrated files from legacy storage")
        }
    }
}
