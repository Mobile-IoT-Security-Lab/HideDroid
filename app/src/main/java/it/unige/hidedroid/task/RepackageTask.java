package it.unige.hidedroid.task;

import android.content.Context;
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
import apktool.module.split.SplitApkMerger;
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
        // be trusted as soon as the app is selected. The trust check only ever
        // looks at the base APK's own network security config, so it runs
        // before any split merging.
        boolean appAlreadyRepackaged = UserCertTrustChecker.apkTrustsUserCertificates(this.ctx.getApplicationContext(), f);
        if (appAlreadyRepackaged) {
            info("APK already trusts user certificates; skipping repackaging");
        }

        Apktool tool = null;
        int minSdk = 19;
        File apkToDecode = f;

        // Config/density/ABI/language splits from an Android App Bundle install
        // can't be decompiled on their own (no manifest/resources.arsc worth
        // patching) and the OS refuses to install a base APK that still requires
        // them (INSTALL_FAILED_MISSING_SPLIT) unless every split is pushed
        // alongside it via install-multiple. Fold them into a single APK first,
        // so decode/build/sign only ever have to deal with one file, installable
        // on its own.
        if (!appAlreadyRepackaged && !this.splitApks.isEmpty()) {
            File mergedApk = new File(f.getParent(), name + "_merged.apk");
            try {
                info("Merging " + this.splitApks.size() + " split APK(s) into " + f.getName() + "...");
                SplitApkMerger.merge(f, this.splitApks, mergedApk, this);
                apkToDecode = mergedApk;
                info("Merge finished: " + mergedApk.getPath());
            } catch (Exception e) {
                if (isDebugEnabled.get()) {
                    Log.e("errorMergingSplits", "Unable to merge split apks: " + e);
                }
                log(Level.WARNING, "Unable to merge split apks", e);
                return false;
            }
        }

        if (!appAlreadyRepackaged) {
            try {
                info("Decompiling " + apkToDecode.getName() + " with apktool into " + decompiledDir.getPath() + "...");
                tool = new Apktool(this.ctx.getApplicationContext(), this);
                ApkInfo info = tool.decodeResources(apkToDecode, decompiledDir);
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

            try {
                // Defends against "<meta-data> requires an android:value or
                // android:resource attribute" install failures: apktool's decode/
                // rebuild round-trip can drop a meta-data's value (e.g. one that
                // pointed at a resource living in a split, for an app that wasn't
                // itself split - see the merge step above for the split case).
                SplitApkMerger.sanitizeManifest(tmpApk, this);
            } catch (Exception e) {
                log(Level.WARNING, "Unable to sanitize manifest, continuing with unsanitized build", e);
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
        }

        // packageName is always this.name - the caller already knows it (it's how
        // the repacked apk gets named), no need to re-derive it by re-parsing the
        // archive: getPackageArchiveInfo() returns null for a still split-required
        // apk (see the merge step above) and would NPE here.
        ApplicationStatus applicationStatus = new ApplicationStatus(
                name, appAlreadyRepackaged, true,
                false, false, false
        );
        applicationStatus.storeStateApp();
        if (appAlreadyRepackaged) {
            PackageNamePrivacyLevel packageNamePrivacyLevel = new PackageNamePrivacyLevel(name, true, 1);
            packageNamePrivacyLevel.storePrivacySettings();
            selectedPrivacyLevelsLock.lock();
            try {
                if (selectedPrivacyLevels.get(name) != null) {
                    Objects.requireNonNull(selectedPrivacyLevels.get(name)).set(1);
                } else {
                    selectedPrivacyLevels.put(name, new AtomicInteger(1));
                }
            } finally {
                selectedPrivacyLevelsLock.unlock();
            }
        }
        Log.d(RepackageTask.class.getName(), "Store on DB Repackaged with successfully");
        return true;
    }

}
