package it.unige.hidedroid.task;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;

import com.dave.realmdatahelper.hidedroid.ApplicationStatus;
import com.dave.realmdatahelper.hidedroid.PackageNamePrivacyLevel;

import org.apache.commons.io.FileUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import apktool.module.Apktool;
import apktool.module.SignatureManager;
import brut.androlib.meta.MetaInfo;
import it.unige.hidedroid.R;

public class RepackageTask extends AbstractTask {
    private final String name;

    public RepackageTask(Context ctx, String name) {
        super(ctx);
        this.name = name;
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

        Apktool tool;
        SignatureManager signManager;

        try {
            tool = new Apktool(this.ctx.getApplicationContext(), this);
            tool.decodeResources(f, decompiledDir);
        } catch (Exception e) {
            if (isDebugEnabled.get()) {
                Log.e("errorDecompilation", "Unable to decompile the apk: " + e);
            }
            log(Level.WARNING, "Unable to decompile the apk", e);
            return false;
        }

        boolean appAlreadyRepackaged;
        try {
            appAlreadyRepackaged = updateNetworkSecurityConfig(decompiledDir);
        } catch (Exception e) {
            if (isDebugEnabled.get()) {
                Log.e("errorUpdateNetworkConf", "Unable to update network security configuration: " + e);
            }
            log(Level.WARNING, "Unable to update network security configuration", e);
            return false;
        }

        if (!appAlreadyRepackaged) {
            int minSdk = 19;
            try {
                MetaInfo meta = tool.build(decompiledDir, tmpApk);
                if (meta.sdkInfo != null) {
                    minSdk = Integer.parseInt(meta.sdkInfo.get("minSdkVersion"));
                }
            } catch (Exception e) {
                if (isDebugEnabled.get()) {
                    Log.e("errorBuildingApk", "Unable to build the new apk: " + e);
                }
                log(Level.WARNING, "Unable to build the new apk", e);
                tmpApk.delete();
                return false;
            }

            try {
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

            if (!signManager.verify(signedApk)) {
                if (isDebugEnabled.get()) {
                    Log.e("errorVerificationApk", "WARNING: Unable to verify the signature of the new apk");
                }
                warning("Unable to verify the signature of the new apk");
                return false;
            }
        }
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
        /*UtilitiesStoreDataOnDb.INSTANCE.storeStateApp(pi.packageName,
                false, true,
                false, false, false);
        */
        Log.d(RepackageTask.class.getName(), "Store on DB Repackaged with successfully");
        // TODO update db con repackaged = True
        return true;
    }

    private boolean updateNetworkSecurityConfig(File outputDir) throws Exception {
        String networkConfigDefaultLocation = "res/xml/network_security_config.xml";

        SAXReader reader = new SAXReader();
        Document manifestDoc = reader.read(new File(outputDir, "AndroidManifest.xml"));

        Element manifestRoot = manifestDoc.getRootElement();
        String networkConfigLocation = ((Element) manifestRoot.selectSingleNode("./application"))
                .attributeValue("networkSecurityConfig");

        File networkConfigFile = new File(outputDir, networkConfigDefaultLocation);

        Element app = (Element) manifestRoot.selectSingleNode("./application");
        //app.remove(app.attribute("testOnly"));
        //app.addAttribute("android:testOnly", "false");
        //FileUtils.writeStringToFile(new File(outputDir, "AndroidManifest.xml"), manifestRoot.asXML(), StandardCharsets.UTF_8);

        if (networkConfigLocation == null) {
            // add @xml/networl_security_config
            app.addAttribute("android:networkSecurityConfig", "@xml/network_security_config");
            FileUtils.writeStringToFile(new File(outputDir, "AndroidManifest.xml"), manifestRoot.asXML(), String.valueOf(StandardCharsets.UTF_8));

            info("Add networkSecurityConfig on Manifest");
            //throw new UnsupportedOperationException(
            //        String.format("Unexpected location of network security configuration: %s",
            //                networkConfigLocation));
        } else {
            networkConfigFile = new File(outputDir, "res/xml/" + networkConfigLocation.split("/")[1] + ".xml");
        }

        if (networkConfigFile.exists() && networkConfigFile.isFile()) {
            // "network_security_config.xml" file found, make it trust user certificates.

            info("Updating network_security_config.xml...");

            Document networkDoc = reader.read(networkConfigFile);
            Element networkRoot = networkDoc.getRootElement();

            Node trustAnchors = networkRoot.selectSingleNode("./base-config/trust-anchors");
            Node baseConfig = networkRoot.selectSingleNode("./base-config");

            // check if certificates are already present, don't add twice!
            if (trustAnchors != null) {
                List<Node> nodes = ((Element) trustAnchors).selectNodes("./certificates");
                boolean system = false;
                boolean user = false;
                for (Node n : nodes) {
                    if (((Element) n).attributeValue("src").equals("user")) {
                        user = true;
                    }
                    if (((Element) n).attributeValue("src").equals("system")) {
                        system = true;
                    }
                    if (system && user) {
                        break;
                    }
                }
                if (system && user) {
                    return true;
                }
                if (!system) {
                    ((Element) trustAnchors).addElement("certificates")
                            .addAttribute("src", "system");
                }
                if (!user) {
                    ((Element) trustAnchors).addElement("certificates")
                            .addAttribute("src", "user");
                }
            } else if (baseConfig != null) {
                Element t = ((Element) baseConfig).addElement("trust-anchors");
                t.addElement("certificates").addAttribute("src", "system");
                t.addElement("certificates").addAttribute("src", "user");
            } else {
                Element t = networkRoot.addElement("base-config").addElement("trust-anchors");
                t.addElement("certificates").addAttribute("src", "system");
                t.addElement("certificates").addAttribute("src", "user");
            }

            FileUtils.writeStringToFile(networkConfigFile, networkDoc.asXML(), String.valueOf(StandardCharsets.UTF_8));
        } else {
            // "network_security_config.xml" file not found, add it and make it trust user
            // certificates.
            if (!networkConfigFile.getParentFile().exists()) {
                networkConfigFile.getParentFile().mkdirs();
            }
            networkConfigFile.createNewFile();
            info("add new network security config file");
            Document networkDoc = DocumentHelper.createDocument();
            info("Create new document");
            Element networkRoot = (Element) networkDoc.addElement("network-security-config");
            Element t = networkRoot.addElement("base-config").addElement("trust-anchors");
            t.addElement("certificates").addAttribute("src", "system");
            t.addElement("certificates").addAttribute("src", "user");
            FileUtils.writeStringToFile(networkConfigFile, networkDoc.asXML(), String.valueOf(StandardCharsets.UTF_8));
        }
        return false;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        super.onPostExecute(result);
    }
}
