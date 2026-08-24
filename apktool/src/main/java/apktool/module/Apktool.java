package apktool.module;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.concurrent.atomic.AtomicReference;

import brut.androlib.ApkBuilder;
import brut.androlib.ApkDecoder;
import brut.androlib.Config;
import brut.androlib.exceptions.AndrolibException;
import brut.androlib.meta.ApkInfo;
import brut.androlib.res.Framework;

/**
 * Thin wrapper around vendored apktool 3.0.3 (src/main/java/brut/**, not
 * published anywhere - see apktool/build.gradle), scoped to what HideDroid
 * needs: decode resources only (never dex/smali) and rebuild with a
 * permissive network security config injected by the library itself.
 */
public class Apktool {
    private static final String APKTOOL_VERSION = "3.0.3";

    private static final AtomicReference<Logger> activeLogger = new AtomicReference<>();
    private static volatile boolean loggingBridged;
    private static volatile boolean deviceFrameworkInstallAttempted;

    private final Config config;

    public Apktool(Context applicationContext, Logger logger) throws IOException, AndrolibException {
        activeLogger.set(logger);
        bridgeApktoolLogging();

        this.config = new Config(APKTOOL_VERSION);
        config.setAaptBinary(getAapt2Binary(applicationContext).getAbsolutePath());
        config.setFrameworkDirectory(getFrameworkDirectory(applicationContext).getAbsolutePath());
        config.setDecodeSources(Config.DecodeSources.NONE);
        config.setDecodeResources(Config.DecodeResources.FULL);
        config.setForced(true);
        installDeviceFrameworkOnce();
    }

    public ApkInfo decodeResources(final File inputApk, final File outputDir) throws AndrolibException {
        ApkDecoder decoder = new ApkDecoder(inputApk, config);
        decoder.decode(outputDir);
        return decoder.getApkInfo();
    }

    /**
     * @param netSecConf when true, apktool injects a network security config
     *                   that trusts user-added certificates (see Config#setNetSecConf).
     */
    public void build(final File inputDir, final File outputApk, boolean netSecConf) throws AndrolibException {
        config.setNetSecConf(netSecConf);
        new ApkBuilder(inputDir, config).build(outputApk);
    }

    /**
     * libaapt2.so ships as a jniLib (apktool/src/main/jniLibs/&lt;abi&gt;/libaapt2.so,
     * statically linked, no DT_NEEDED - see that directory's README), not an
     * asset: the OS already extracted it into nativeLibraryDir with exec
     * permission at install time (android:extractNativeLibs="true" +
     * useLegacyPackaging are required for that and are already set in
     * app/build.gradle/AndroidManifest.xml), so there is nothing to copy here.
     * Covers arm64-v8a/armeabi-v7a/x86_64; no build exists yet for 32-bit x86.
     */
    private static File getAapt2Binary(Context context) throws IOException {
        File aapt2 = new File(context.getApplicationInfo().nativeLibraryDir, "libaapt2.so");
        if (!aapt2.isFile()) {
            throw new IOException("No libaapt2.so for this device's ABI ("
                    + android.os.Build.SUPPORTED_ABIS[0] + ") in nativeLibraryDir. "
                    + "See apktool/src/main/jniLibs/README.md.");
        }
        return aapt2;
    }

    /**
     * apktool-lib bundles a generic "android-framework.jar" (Framework's own
     * built-in fallback) that doesn't necessarily match this device's actual
     * SDK level. Decoding/rebuilding against a mismatched framework can turn
     * an enum-typed attribute the device's own resources use (e.g. something
     * new in a values-vNN/ override) into a raw numeric literal that aapt2
     * link then rejects ("expected enum but got (raw string) N"). Installing
     * the device's own /system/framework/framework-res.apk as framework id 1
     * - world-readable on stock AOSP policy - avoids that mismatch entirely.
     * Falls back to the generic bundled one (Framework's own lazy handling)
     * if that file isn't present/readable, e.g. some locked-down OEM builds.
     */
    private synchronized void installDeviceFrameworkOnce() {
        if (deviceFrameworkInstallAttempted) {
            return;
        }
        deviceFrameworkInstallAttempted = true;

        File deviceFramework = new File("/system/framework/framework-res.apk");
        if (!deviceFramework.isFile() || !deviceFramework.canRead()) {
            return;
        }
        try {
            new Framework(config).install(deviceFramework);
        } catch (AndrolibException ignored) {
            // Fall back to the generic bundled framework.
        }
    }

    private static File getFrameworkDirectory(Context context) {
        // apktool's own Framework class self-extracts its bundled android-framework.jar
        // here on first use (build step only) - nothing for us to copy.
        File frameworkDir = new File(context.getFilesDir(), "framework");
        frameworkDir.mkdirs();
        return frameworkDir;
    }

    /**
     * apktool-lib logs through java.util.logging (brut.common.Log, tagged by
     * dotted class name e.g. "brut.androlib.ApkBuilder") rather than an
     * injectable callback. Its tags nest under the "brut" logger by JUL's
     * usual dot-hierarchy, so one handler there catches everything.
     */
    private static synchronized void bridgeApktoolLogging() {
        if (loggingBridged) {
            return;
        }
        java.util.logging.Logger.getLogger("brut").addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                Logger logger = activeLogger.get();
                if (logger == null) {
                    return;
                }
                String message = record.getMessage();
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    logger.warning(message);
                } else {
                    logger.info(message);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        loggingBridged = true;
    }
}
