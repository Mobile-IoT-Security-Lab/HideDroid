package it.unige.hidedroid.utils

import android.os.Build
import android.util.Log
import java.io.*
import java.util.*

object RootUtil {
    val isDeviceRooted: Boolean
        get() = checkRootMethod1() || checkRootMethod2() || checkRootMethod3()

    private fun checkRootMethod1(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf("/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
                            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
                            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
                            "/su/bin/su")
        for (path in paths) {
            if (File(path).exists()) return true
        }
        return false
    }

    private fun checkRootMethod3(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val `in` = BufferedReader(InputStreamReader(process.inputStream))
            if (`in`.readLine() != null) true else false
        } catch (t: Throwable) {
            false
        } finally {
            process?.destroy()
        }
    }

    fun canRunRootCommands(): Boolean {
        var retval = false
        val suProcess: Process
        try {
            suProcess = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(suProcess.outputStream)
            val osRes = DataInputStream(suProcess.inputStream)
            if (null != os && null != osRes) {
                // Getting the id of the current user to check if this is root
                os.writeBytes("id\n")
                os.flush()
                val currUid = osRes.readLine()
                var exitSu = false
                if (null == currUid) {
                    retval = false
                    exitSu = false
                    Log.d("ROOT", "Can't get root access or denied by user")
                } else if (currUid.contains("uid=0")) {
                    retval = true
                    exitSu = true
                    Log.d("ROOT", "Root access granted")
                } else {
                    retval = false
                    exitSu = true
                    Log.d("ROOT", "Root access rejected: $currUid")
                }
                if (exitSu) {
                    os.writeBytes("exit\n")
                    os.flush()
                }
            }
        } catch (e: Exception) {
            // Can't get root !
            // Probably broken pipe exception on trying to write to output stream (os) after su failed, meaning that the device is not rooted
            retval = false
            Log.d("ROOT", "Root access rejected [" + e.javaClass.name + "] : " + e.message)
        }
        return retval
    }

    fun execute(commands: ArrayList<String>?): Boolean {
        if (commands == null || commands.isEmpty()) {
            return false
        }
        try {
            val suProcess = Runtime.getRuntime().exec("su")
            DataOutputStream(suProcess.outputStream).use { os ->
                // Merge stderr into stdout so a full stderr buffer cannot deadlock us.
                os.writeBytes("exec 2>&1\n")
                os.flush()

                for (currCommand in commands) {
                    // Run each command in a subshell and emit its exit code so we can
                    // distinguish a failed command from the final "exit" command.
                    os.writeBytes("( $currCommand ); echo HIDEROOT:$?\n")
                    os.flush()
                }
                os.writeBytes("exit\n")
                os.flush()

                val exitCodes = mutableListOf<Int>()
                BufferedReader(InputStreamReader(suProcess.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let {
                            if (it.startsWith("HIDEROOT:")) {
                                exitCodes.add(it.substringAfter("HIDEROOT:").toIntOrNull() ?: -1)
                            }
                        }
                    }
                }
                suProcess.waitFor()
                return exitCodes.isNotEmpty() && exitCodes.all { it == 0 }
            }
        } catch (ex: Exception) {
            Log.w("ROOT", "Error executing root action", ex)
            return false
        }
    }

    fun isSystemCaInstalled(nameCert: String): Boolean {
        return execute(arrayListOf("[ -f /system/etc/security/cacerts/$nameCert ]"))
    }

    fun deleteSystemCA(nameCert: String): Boolean {
        val systemPath = "/system/etc/security/cacerts/$nameCert"
        val deleteScript = """
            mount -o remount,rw /system &&
            rm -f '$systemPath' &&
            mount -o remount,ro /system
        """.trimIndent()
        return execute(arrayListOf(deleteScript)) && !isSystemCaInstalled(nameCert)
    }

    fun deleteUserCA(nameCert: String): Boolean {
        // Android has no public API to remove a user CA. On rooted devices we can delete the
        // per-user certificate file directly from the keychain directories. The exact path varies
        // by version and user, so try the common locations.
        val deleteScript = """
            for path in \
                /data/misc/keychain/cacerts-added/'$nameCert' \
                /data/misc/user/0/cacerts-added/'$nameCert' \
                /data/misc/user/0/cacerts-installed/'$nameCert'
            do
                [ -f \"${'$'}path\" ] && rm -f \"${'$'}path\"
            done
        """.trimIndent()
        return execute(arrayListOf(deleteScript))
    }

    fun installSystemCA(nameCert: String, pemContentCert: String): Boolean {
        // Write to a temp file, copy into the system CA store with correct permissions,
        // then remount read-only. Using && ensures any failure aborts the script.
        val tempPath = "/data/local/tmp/$nameCert"
        val systemPath = "/system/etc/security/cacerts/$nameCert"
        val installScript = """
            mount -o remount,rw /system &&
            cat <<'EOF' > '$tempPath'
$pemContentCert
EOF
            cp '$tempPath' '$systemPath' &&
            chmod 644 '$systemPath' &&
            chown root:root '$systemPath' &&
            mount -o remount,ro /system &&
            rm -f '$tempPath'
        """.trimIndent()

        return execute(arrayListOf(installScript)) && isSystemCaInstalled(nameCert)
    }


}