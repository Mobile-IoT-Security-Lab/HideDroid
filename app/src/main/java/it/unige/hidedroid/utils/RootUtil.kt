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
        var retval = false
        try {
            if (null != commands && commands.size > 0) {
                val suProcess = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(suProcess.outputStream)

                // Execute commands that require root access
                for (currCommand in commands) {
                    os.writeBytes("$currCommand \n")
                    os.flush()
                }
                os.writeBytes("exit\n")
                os.flush()
                try {
                    val suProcessRetval = suProcess.waitFor()
                    retval = 255 != suProcessRetval
                } catch (ex: Exception) {
                    Log.e("ROOT", "Error executing root action", ex)
                }
            }
        } catch (ex: IOException) {
            Log.w("ROOT", "Can't get root access", ex)
        } catch (ex: SecurityException) {
            Log.w("ROOT", "Can't get root access", ex)
        } catch (ex: Exception) {
            Log.w("ROOT", "Error executing internal operation", ex)
        }
        return retval
    }

    fun installSystemCA(nameCert:String, pemContentCert:String):Boolean{
        val retval = execute(arrayListOf("mount -o rw,remount /", // enable rw
                "mount -o rw,remount /system", //enable rw on system
                "echo \"$pemContentCert\" >> $nameCert", // echo content certificate on file
                "mv $nameCert /system/etc/security/cacerts", // move
                "chmod 664 /system/etc/security/cacerts/${nameCert}")) //enable read and execution permission

        return retval
    }


}