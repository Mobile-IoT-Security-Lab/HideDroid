package it.unige.hidedroid.task;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.dave.realmdatahelper.hidedroid.ApplicationStatus;
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import apktool.module.Apktool;
import apktool.module.SignatureManager;
import brut.androlib.meta.ApkInfo;
import it.unige.hidedroid.R;
import it.unige.hidedroid.utils.HideDroidStorage;
import it.unige.hidedroid.utils.UserCertTrustChecker;

public class RepackageTask extends AbstractTask {
    private final String name;
    private final List<File> splitApks;

    public RepackageTask(Context ctx, String name) {
        this(ctx, name, Collections.emptyList());
    }

    /**
     * @param splitApks split APKs (config/density/ABI/language) from an Android
     *                  App Bundle install of the same app, if any. Each is only
     *                  re-signed with the same key as the rebuilt base APK, never
     *                  decompiled - they carry no manifest/resources.arsc worth
     *                  patching, they just need to match the base's new signature.
     */
    public RepackageTask(Context ctx, String name, List<File> splitApks) {
        super(ctx);
        this.name = name;
        this.splitApks = splitApks;
    }

    @Override
    protected int getTitle() {
        return R.string.decode_run_title;
    }

    @Override
    protected boolean process(Map<String, AtomicInteger> selectedPrivacyLevels, ReentrantLock selectedPrivacyLevelsLock, final File f,
                              AtomicBoolean isDebugEnabled) {
        File decompiledDir = new File(f.getParent(), name + "_decompiled");
        File tmpApk = new File(f.getParent(), name + "_tmp.apk");
        File signedApk = new File(f.getParent(), name + "_signed.apk");

        // Fast path: if the APK already trusts user certificates, there is no
        // need to decode, patch, rebuild and sign it. The user-installed CA will
        // be trusted as soon as the app is selected.
        boolean appAlreadyRepackaged = UserCertTrustChecker.apkTrustsUserCertificates(this.ctx.getApplicationContext(), f);
        if (appAlreadyRepackaged) {
            info("APK already trusts user certificates; skipping repackaging");
        }

        Apktool tool = null;
        int minSdk = 19;

        if (!appAlreadyRepackaged) {
            try {
                info("Decompiling " + f.getName() + " with apktool into " + decompiledDir.getPath() + "...");
                tool = new Apktool(this.ctx.getApplicationContext(), this);
                ApkInfo info = tool.decodeResources(f, decompiledDir);
                String minSdkVersion = info.getSdkInfo().getMinSdkVersion();
                if (minSdkVersion != null) {
                    minSdk = Integer.parseInt(minSdkVersion);
                }
                info("Decompilation finished (minSdkVersion=" + minSdkVersion + ")");
            } catch (Exception e) {
                if (isDebugEnabled.get()) {
                    Log.e("errorDecompilation", "Unable to decompile the apk: " + e);
                }
                log(Level.WARNING, "Unable to decompile the apk", e);
                return false;
            }
        }

        if (!appAlreadyRepackaged) {
            try {
                info("Rebuilding APK with aapt2 (injecting permissive network security config)...");
                // apktool injects a network security config trusting user-added
                // certificates directly during the build; no manual XML patching needed.
                tool.build(decompiledDir, tmpApk, true);
                info("Rebuild finished: " + tmpApk.getPath());
            } catch (Exception e) {
                if (isDebugEnabled.get()) {
                    Log.e("errorBuildingApk", "Unable to build the new apk: " + e);
                }
                log(Level.WARNING, "Unable to build the new apk", e);
                tmpApk.delete();
                return false;
            }

            SignatureManager signManager;
            try {
                info("Signing " + tmpApk.getName() + " (minSdk=" + minSdk + ")...");
                signManager = new SignatureManager(this.ctx.getApplicationContext(), this);
                signManager.sign(tmpApk, signedApk, minSdk);
            } catch (Exception e) {
                if (isDebugEnabled.get()) {
                    Log.e("errorSigningApk", "Unable to sign the new apk: " + e);
                }
                log(Level.WARNING, "Unable to sign the new apk", e);
                return false;
            } finally {
                tmpApk.delete();
            }

            info("Verifying signature...");
            if (!signManager.verify(signedApk)) {
                if (isDebugEnabled.get()) {
                    Log.e("errorVerificationApk", "WARNING: Unable to verify the signature of the new apk");
                }
                warning("Unable to verify the signature of the new apk");
                return false;
            }

            try {
                File repackedApk = HideDroidStorage.moveToRepackedLocation(name, signedApk);
                info("Repacked APK moved to " + repackedApk.getPath());
            } catch (Exception e) {
                if (isDebugEnabled.get()) {
                    Log.e("errorMovingApk", "Unable to move the repacked apk: " + e);
                }
                log(Level.WARNING, "Unable to move the repacked apk to shared storage", e);
                return false;
            }

            // Split APKs (config/density/ABI/language) just need to match the base's new
            // signature to install together - resign each directly, no decompile/rebuild.
            // Best-effort: a failed split shouldn't block the (already valid) base APK.
            for (File split : splitApks) {
                File signedSplit = HideDroidStorage.getRepackedSplitFile(name, split.getName());
                try {
                    info("Resigning split " + split.getName() + "...");
                    signManager.sign(split, signedSplit, minSdk);
                    if (signManager.verify(signedSplit)) {
                        info("Resigned split saved to " + signedSplit.getPath());
                    } else {
                        warning("Unable to verify signature of resigned split " + split.getName());
                    }
                } catch (Exception e) {
                    if (isDebugEnabled.get()) {
                        Log.e("errorSigningSplit", "Unable to resign split " + split.getName() + ": " + e);
                    }
                    log(Level.WARNING, "Unable to resign split " + split.getName(), e);
                }
            }
        }

        @SuppressWarnings("deprecation")
        PackageInfo pi = this.ctx.getPackageManager().getPackageArchiveInfo(f.getPath(), 0);
        ApplicationStatus applicationStatus = new ApplicationStatus(
                pi.packageName, appAlreadyRepackaged, true,
                false, false, false
        );
        applicationStatus.storeStateApp();
        if (appAlreadyRepackaged) {
            PackageNamePrivacyLevel packageNamePrivacyLevel = new PackageNamePrivacyLevel(pi.packageName, true, 1);
            packageNamePrivacyLevel.storePrivacySettings();
            selectedPrivacyLevelsLock.lock();
            try {
                if (selectedPrivacyLevels.get(pi.packageName) != null) {
                    Objects.requireNonNull(selectedPrivacyLevels.get(pi.packageName)).set(1);
                } else {
                    selectedPrivacyLevels.put(pi.packageName, new AtomicInteger(1));
                }
            } finally {
                selectedPrivacyLevelsLock.unlock();
            }
        }
        Log.d(RepackageTask.class.getName(), "Store on DB Repackaged with successfully");
        return true;
    }

}
