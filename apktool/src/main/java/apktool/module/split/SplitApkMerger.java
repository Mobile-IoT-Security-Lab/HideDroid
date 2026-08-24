package apktool.module.split;

import com.reandroid.apk.APKLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.utils.collection.CollectionUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

import apktool.module.Logger;

/**
 * Merges an Android App Bundle install's base APK and its config/density/ABI/
 * language splits into a single self-contained APK - same approach as
 * AntiSplit-M, built on the REAndroid ARSCLib library it wraps (pure-Java
 * resources.arsc/manifest/dex parsing, no aapt2/native code involved).
 *
 * Run before decode/build so apktool only ever has to deal with one APK, and
 * the result installs with a plain install intent instead of install-multiple.
 */
public class SplitApkMerger {

    public static void merge(File baseApk, List<File> splitApks, File outputApk, Logger logger) throws IOException {
        APKLogger apkLogger = bridge(logger);
        ApkBundle bundle = new ApkBundle();
        bundle.setAPKLogger(apkLogger);
        bundle.addModule(ApkModule.loadApkFile(baseApk, "base"));
        int i = 0;
        for (File split : splitApks) {
            bundle.addModule(ApkModule.loadApkFile(split, "split_" + (i++)));
        }
        ApkModule merged = bundle.mergeModules(false);
        doSanitizeManifest(merged, apkLogger);
        merged.refreshTable();
        merged.refreshManifest();
        merged.writeApk(outputApk);
        merged.close();
        bundle.close();
    }

    /**
     * Strips split-only manifest plumbing (isSplitRequired/splitTypes, the
     * "com.android.vending.splits" meta-data pointing at the split table) and
     * any leftover &lt;meta-data&gt; with neither android:value nor
     * android:resource - the latter is a hard install-parse failure
     * ("&lt;meta-data&gt; requires an android:value or android:resource attribute"),
     * which apktool's decode/rebuild round-trip can produce when a meta-data's
     * referenced resource lived in a split that wasn't merged in. Safe to run
     * on a plain (non-split) APK too - every attribute/element it looks for is
     * a no-op when absent.
     */
    public static void sanitizeManifest(File apk, Logger logger) throws IOException {
        ApkModule apkModule = ApkModule.loadApkFile(apk);
        doSanitizeManifest(apkModule, bridge(logger));
        apkModule.refreshTable();
        apkModule.refreshManifest();
        apkModule.writeApk(apk);
        apkModule.close();
    }

    private static void doSanitizeManifest(ApkModule apkModule, APKLogger logger) {
        if (!apkModule.hasAndroidManifest()) {
            return;
        }
        AndroidManifestBlock manifest = apkModule.getAndroidManifest();

        AndroidManifestHelper.removeAttributeFromManifestById(manifest, AndroidManifest.ID_requiredSplitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestById(manifest, AndroidManifest.ID_splitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_splitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestByName(manifest, AndroidManifest.NAME_requiredSplitTypes, logger);
        AndroidManifestHelper.removeAttributeFromManifestAndApplication(manifest,
                AndroidManifest.ID_isSplitRequired, logger, AndroidManifest.NAME_isSplitRequired);

        ResXmlElement application = manifest.getApplicationElement();
        if (application == null) {
            return;
        }
        for (ResXmlElement meta : AndroidManifestHelper.listSplitRequired(application)) {
            application.remove(meta);
        }
        removeMalformedMetaData(application, logger);
        manifest.refresh();
    }

    private static void removeMalformedMetaData(ResXmlElement application, APKLogger logger) {
        List<ResXmlElement> broken = CollectionUtil.toList(application.getElements(element ->
                element.equalsName(AndroidManifest.TAG_meta_data)
                        && element.searchAttributeByResourceId(AndroidManifest.ID_value) == null
                        && element.searchAttributeByResourceId(AndroidManifest.ID_resource) == null));
        for (ResXmlElement meta : broken) {
            if (logger != null) {
                logger.logMessage("Removed malformed <meta-data> (no value/resource): "
                        + AndroidManifestBlock.getAndroidNameValue(meta));
            }
            application.remove(meta);
        }
    }

    private static APKLogger bridge(Logger logger) {
        return new APKLogger() {
            @Override
            public void logMessage(String msg) {
                if (logger != null) logger.info(msg);
            }

            @Override
            public void logError(String msg, Throwable tr) {
                if (logger != null) logger.log(Level.WARNING, msg, tr);
            }

            @Override
            public void logVerbose(String msg) {
            }
        };
    }
}
