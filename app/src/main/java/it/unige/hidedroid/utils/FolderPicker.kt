package it.unige.hidedroid.utils

import android.app.Activity
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import it.unige.hidedroid.R
import java.io.File

/**
 * Minimal folder browser (AlertDialog.setItems, no new layout) for the
 * repack output-folder setup step. Stays on plain File/paths - a SAF tree
 * URI can't be handed to the aapt2 subprocess, which needs a real path.
 */
object FolderPicker {

    fun choose(activity: Activity, start: File = Environment.getExternalStorageDirectory(), onChosen: (File) -> Unit) {
        show(activity, if (start.isDirectory) start else Environment.getExternalStorageDirectory(), onChosen)
    }

    private fun show(activity: Activity, dir: File, onChosen: (File) -> Unit) {
        val subDirs = (dir.listFiles { f -> f.isDirectory && !f.isHidden } ?: emptyArray())
                .sortedBy { it.name.lowercase() }

        val entries = mutableListOf(activity.getString(R.string.folder_picker_use_this, dir.path))
        val parent = dir.parentFile
        if (parent != null) {
            entries.add(activity.getString(R.string.folder_picker_up))
        }
        entries.addAll(subDirs.map { it.name })

        AlertDialog.Builder(activity, R.style.CustomAlertDialogRounded)
                .setTitle(R.string.folder_picker_title)
                .setItems(entries.toTypedArray()) { _, which ->
                    val upOffset = if (parent != null) 1 else 0
                    when {
                        which == 0 -> onChosen(dir)
                        parent != null && which == 1 -> show(activity, parent, onChosen)
                        else -> show(activity, subDirs[which - 1 - upOffset], onChosen)
                    }
                }
                .setNegativeButton(R.string.folder_picker_cancel, null)
                .show()
    }
}
