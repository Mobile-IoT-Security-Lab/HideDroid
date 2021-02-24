package apktool.module;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.text.TextUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import brut.androlib.Androlib;
import brut.androlib.AndrolibException;
import brut.androlib.ApkDecoder;
import brut.androlib.ApkOptions;
import brut.androlib.meta.MetaInfo;
import brut.common.BrutException;
import brut.util.Logger;

public class Apktool {
    private static ApkOptions options = ApkOptions.INSTANCE;
    private Androlib androlib;

    private Logger logger;

    public Apktool(Context applicationContext, Logger logger) throws IOException {
        if (TextUtils.isEmpty(Apktool.options.aaptPath) ||
                TextUtils.isEmpty(Apktool.options.frameworkFolderLocation)) {
            this.copyAaptBinary(applicationContext.getAssets(), applicationContext.getFilesDir());
            this.copyFramework(applicationContext.getAssets(), applicationContext.getFilesDir());
        }
        this.logger = logger;
        this.androlib = new Androlib(options, this.logger);
    }

    private void copyAaptBinary(AssetManager assets, File outputDir) throws IOException {
        String arch = Build.SUPPORTED_32_BIT_ABIS[0];
        if (arch.startsWith("arm")) {
            arch = "arm";
        } else if (arch.startsWith("x86")) {
            arch = "x86";
        }
        File aapt = new File(outputDir, "aapt");
        try (InputStream in = assets.open(arch + "/aapt"); OutputStream out = new FileOutputStream(aapt)) {
            IOUtils.copy(in, out);
            aapt.setExecutable(true);
        }
        Apktool.options.aaptPath = aapt.getAbsolutePath();
    }

    private void copyFramework(AssetManager assets, File outputDir) throws IOException {
        File frameworkFile = new File(outputDir, "framework/1.apk");
        File frameworkDir = frameworkFile.getParentFile();
        frameworkDir.mkdirs();
        try (InputStream in = assets.open("android-framework.jar"); OutputStream out = new FileOutputStream(frameworkFile)) {
            IOUtils.copy(in, out);
        }
        Apktool.options.frameworkFolderLocation = frameworkDir.getAbsolutePath();
    }

    public void decodeResources(final File inputApk, final File outputDir) throws AndrolibException {
        ApkDecoder decoder = new ApkDecoder(this.androlib, this.logger);

        decoder.setDecodeResources(ApkDecoder.DECODE_RESOURCES_FULL);
        decoder.setDecodeSources(ApkDecoder.DECODE_SOURCES_NONE);
        decoder.setForceDelete(true);
        decoder.setApkFile(inputApk);
        decoder.setOutDir(outputDir);

        decoder.decode();
    }

    public MetaInfo build(final File inputDir, final File outputApk) throws BrutException {
        return this.androlib.build(inputDir, outputApk);
    }
}
