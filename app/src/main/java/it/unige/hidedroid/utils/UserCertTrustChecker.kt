@file:Suppress("DEPRECATION")

package it.unige.hidedroid.utils

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import net.dongliu.apk.parser.ApkParser
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Lightweight check to determine whether an APK (or its installed instance)
 * already declares a network_security_config that trusts user-added certificates.
 *
 * If it does, HideDroid can skip repackaging on Android 7+ because the app
 * will already trust the HideDroid CA installed in the user store.
 */
object UserCertTrustChecker {

    private const val TAG = "UserCertTrustChecker"

    /**
     * Checks the APK manifest for a networkSecurityConfig reference and, when the
     * package is installed, parses the live (binary) resource of the target app
     * to look for a user certificate trust anchor.
     *
     * Reading the raw res/xml/any_name.xml file inside the APK directly does not work
     * because it is a compiled binary XML; we therefore use the installed app's
     * Resources when possible.
     */
    @JvmStatic
    fun apkTrustsUserCertificates(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        val manifest = readManifest(apkFile) ?: return false
        val configRef = extractNetworkSecurityConfigRef(manifest) ?: return false
        val configName = configRef.substringAfterLast("/").replaceFirst("^\\+", "")
        val packageName = extractPackageName(manifest) ?: return false

        return try {
            val appContext = context.createPackageContext(packageName, 0)
            val resId = appContext.resources.getIdentifier(configName, "xml", packageName)
            if (resId == 0) {
                Log.d(TAG, "Network security config resource not found for $packageName")
                return false
            }
            hasUserTrustAnchor(appContext.resources, resId).also {
                Log.d(TAG, "$packageName already trusts user certificates: $it")
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // App is not installed; fall back to false. The heavy Apktool path in
            // RepackageTask will still detect a pre-existing user trust anchor.
            Log.d(TAG, "$packageName not installed, cannot parse live resources")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse network_security_config for $packageName", e)
            false
        }
    }

    private fun readManifest(apkFile: File): String? {
        return try {
            ApkParser(apkFile).use { it.manifestXml }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read manifest from ${apkFile.name}", e)
            null
        }
    }

    private fun extractNetworkSecurityConfigRef(manifest: String): String? {
        return Regex("""networkSecurityConfig\s*=\s*["']([^"']+)["']""").find(manifest)
                ?.groupValues?.get(1)
                ?.also { Log.d(TAG, "Found networkSecurityConfig reference: $it") }
    }

    private fun extractPackageName(manifest: String): String? {
        return Regex("""package\s*=\s*["']([^"']+)["']""").find(manifest)
                ?.groupValues?.get(1)
    }

    private fun hasUserTrustAnchor(resources: android.content.res.Resources, resId: Int): Boolean {
        val parser = resources.getXml(resId)
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "certificates") {
                val src = parser.getAttributeValue(null, "src")
                if (src.equals("user", ignoreCase = true)) {
                    parser.close()
                    return true
                }
            }
            eventType = parser.next()
        }
        parser.close()
        return false
    }
}
