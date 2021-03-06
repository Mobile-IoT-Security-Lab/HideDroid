/*  NetBare - An android network capture and injection library.
 *  Copyright (C) 2018-2019 Megatron King
 *  Copyright (C) 2018-2019 GuoShi
 *
 *  NetBare is free software: you can redistribute it and/or modify it under the terms
 *  of the GNU General Public License as published by the Free Software Found-
 *  ation, either version 3 of the License, or (at your option) any later version.
 *
 *  NetBare is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with NetBare.
 *  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.megatronking.netbare.ssl;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.security.KeyChain;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.github.megatronking.netbare.NetBareLog;
import com.github.megatronking.netbare.NetBareUtils;

import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;

/**
 * A java keystore to manage root certificate.
 *
 * @author Megatron King
 * @since 2018-11-10 20:06
 */
public class JKS {

    public static final String KEY_STORE_FILE_EXTENSION = ".p12";
    public static final String KEY_PEM_FILE_EXTENSION = ".pem";
    public static final String KEY_JKS_FILE_EXTENSION = ".jks";
    public static final String CRT_FILE_EXTENSION = ".crt";
    public static final String BEGIN_CERT = "-----BEGIN CERTIFICATE-----";
    public static final String END_CERT = "-----END CERTIFICATE-----";
    public final static String LINE_SEPARATOR = System.getProperty("line.separator");
    public static final String TAG = JKS.class.getName();
    public static final int REQUEST_CODE_CERTIFICATE_INSTALLED = 30;

    private final File keystoreDir;
    private final String alias;
    private final char[] password;
    private final String commonName;
    private final String organization;
    private final String organizationalUnitName;
    private final String certOrganization;
    private final String certOrganizationalUnitName;

    public JKS(@NonNull Context context, @NonNull String alias, @NonNull char[] password,
               @NonNull String commonName, @NonNull String organization,
               @NonNull String organizationalUnitName, @NonNull String certOrganization,
               @NonNull String certOrganizationalUnitName) {
        this.keystoreDir = context.getCacheDir();
        this.alias = alias;
        this.password = password;
        this.commonName = commonName;
        this.organization = organization;
        this.organizationalUnitName = organizationalUnitName;
        this.certOrganization = certOrganization;
        this.certOrganizationalUnitName = certOrganizationalUnitName;
        createKeystore();
    }

    String alias() {
        return alias;
    }

    char[] password() {
        return password;
    }

    String commonName() {
        return commonName;
    }

    String organization() {
        return organization;
    }

    String organizationalUnitName() {
        return organizationalUnitName;
    }

    String certOrganisation() {
        return certOrganization;
    }

    String certOrganizationalUnitName() {
        return certOrganizationalUnitName;
    }

    public boolean isInstalled() {
        return aliasFile(KEY_STORE_FILE_EXTENSION).exists() &&
                aliasFile(KEY_PEM_FILE_EXTENSION).exists() &&
                aliasFile(KEY_JKS_FILE_EXTENSION).exists();
    }

    public File aliasFile(String fileExtension) {
        return new File(keystoreDir, alias + fileExtension);
    }

    private void createKeystore() {
        if (aliasFile(KEY_STORE_FILE_EXTENSION).exists() &&
                aliasFile(KEY_PEM_FILE_EXTENSION).exists()) {
            return;
        }

        // Generate keystore in the async thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                CertificateGenerator generator = new CertificateGenerator();
                KeyStore keystore;
                OutputStream os = null;
                Writer sw = null;
                JcaPEMWriter pw = null;
                try {
                    keystore = generator.generateRoot(JKS.this);
                    os = new FileOutputStream(aliasFile(KEY_STORE_FILE_EXTENSION));
                    keystore.store(os, password());

                    Certificate cert = keystore.getCertificate(alias());
                    sw = new FileWriter(aliasFile(KEY_PEM_FILE_EXTENSION));
                    pw = new JcaPEMWriter(sw);
                    pw.writeObject(cert);
                    pw.flush();
                    NetBareLog.i("Generate keystore succeed.");
                } catch (Exception e) {
                    NetBareLog.e(e.getMessage());
                } finally {
                    NetBareUtils.closeQuietly(os);
                    NetBareUtils.closeQuietly(sw);
                    NetBareUtils.closeQuietly(pw);
                }
            }
        }).start();
    }

    /**
     * Whether the certificate with given alias has been installed.
     *
     * @param context Any context.
     * @param alias   Key store alias.
     * @return True if the certificate has been installed.
     */
    public static boolean isInstalled(Context context, String alias) {
        return new File(context.getCacheDir(),
                alias + KEY_JKS_FILE_EXTENSION).exists();
    }

    public static boolean isInstalledOnDevice(Context context, String alias) {
        try {
            SharedPreferences sp = context.getSharedPreferences("configuration", Context.MODE_PRIVATE);
            String value = sp.getString("instanceAppId", "");
            if (value == null || value.equals("")) {
                return false;
            }
            String issuer = alias + "_" + value;
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            if (ks != null) {
                ks.load(null, null);
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    String aliasToCheck = aliases.nextElement();
                    java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) ks.getCertificate(aliasToCheck);
                    if (cert.getIssuerDN().getName().contains(issuer))
                        return true;
                }
            }
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Install the self-signed root certificate.
     *
     * @param context Any context.
     * @param name    Key chain name.
     * @throws IOException If an IO error has occurred.
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public static void install(AppCompatActivity context, String name, String alias) throws IOException {
        FileInputStream is = null;
        byte[] keychain;
        SharedPreferences sp = context.getSharedPreferences("configuration", Context.MODE_PRIVATE);
        String value = sp.getString("instanceAppId", "");
        if (value == null || value.equals("")) {
            return;
        }
        String crtName = alias + "_" + value;
        try {
            is = new FileInputStream(new File(context.getCacheDir(), crtName + KEY_PEM_FILE_EXTENSION));
            keychain = new byte[is.available()];
            int len = is.read(keychain);
            if (len != keychain.length) {
                throw new IOException("Install JKS failed, len: " + len);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P + 2) {
                File hideDroidFolder = new File(Environment.getExternalStorageDirectory(), "HideDroid");
                if (!hideDroidFolder.exists()) {
                    hideDroidFolder.mkdir();
                }
                File f = new File(hideDroidFolder, alias + CRT_FILE_EXTENSION);
                if (!f.exists()) {
                    f.createNewFile();
                }
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(keychain);
                    fos.flush();
                }
                File internalCert = new File(context.getCacheDir(), alias + KEY_JKS_FILE_EXTENSION);
                if (!internalCert.exists()) {
                    internalCert.createNewFile();
                }
                try (FileOutputStream fos = new FileOutputStream(internalCert)) {
                    fos.write(keychain);
                    fos.flush();
                    return;
                }
            }
        } finally {
            NetBareUtils.closeQuietly(is);
        }

        Intent intent = new Intent(context, CertificateInstallActivity.class);
        intent.putExtra(KeyChain.EXTRA_CERTIFICATE, keychain);
        intent.putExtra(KeyChain.EXTRA_NAME, name);
        intent.putExtra(CertificateInstallActivity.EXTRA_ALIAS, crtName);
        if (!(context instanceof AppCompatActivity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivityForResult(intent, REQUEST_CODE_CERTIFICATE_INSTALLED);
    }

    private static byte[] getDigest(byte[] toHash) {
        MessageDigest md;

        try {
            md = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException nsa) {
            throw new RuntimeException("no such algorithm");
        }

        return md.digest(toHash);
    }

    public String getPemCACertificate() throws KeyStoreException {
        KeyStore ks = KeyStore.getInstance(new CertificateGenerator().keyStoreType());
        FileInputStream is = null;
        String pemCertificate = null;
        try {
            is = new FileInputStream(aliasFile(JKS.KEY_STORE_FILE_EXTENSION));
            ks.load(is, password());
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias());
            StringWriter writer = new StringWriter();
            JcaPEMWriter pemWriter = new JcaPEMWriter(writer);
            pemWriter.writeObject(cert);
            pemWriter.flush();
            pemWriter.close();
            //Log.d(TAG, "PEM format system CA: "+writer.toString());
            pemCertificate = writer.toString();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (CertificateException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            NetBareUtils.closeQuietly(is);
            return pemCertificate;
        }
    }

    public String getHashSystemCACertificate() throws KeyStoreException {
        KeyStore ks = KeyStore.getInstance(new CertificateGenerator().keyStoreType());
        FileInputStream is = null;
        String nameSystemCertificate = null;
        try {
            is = new FileInputStream(aliasFile(JKS.KEY_STORE_FILE_EXTENSION));
            ks.load(is, password());
            X509Certificate cert = (X509Certificate) ks.getCertificate(alias());
            String subjectHashOld = getSubjectHashOld(cert);
            nameSystemCertificate = subjectHashOld + ".0";
            //Log.d(TAG, "Name system CA: "+nameSystemCertificate.toString());

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (CertificateException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            NetBareUtils.closeQuietly(is);
            return nameSystemCertificate;

        }

    }

    private String getSubjectHashOld(X509Certificate x509Cert) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(x509Cert.getSubjectX500Principal().getEncoded());
            byte[] hashBytes = truncatedHash(digest, 4);
            String subjectHashOld = getStringCertificate(hashBytes);
            Log.d(TAG, subjectHashOld);
            return subjectHashOld;

        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }


    private byte[] truncatedHash(byte[] hash, int truncatedLength) {
        if (truncatedLength < 1 || hash.length < 1)
            return new byte[0];

        byte[] result = new byte[truncatedLength];

        for (int i = 0; i < truncatedLength; ++i)
            result[truncatedLength - 1 - i] = hash[i];

        return result;
    }

    private String getStringCertificate(byte[] bytes) {
        StringBuffer encodedName = new StringBuffer();
        for (byte b : bytes) {
            // byte to unsigned int
            Log.d(TAG, String.format("%02X ", b & 0xFF));
            encodedName.append(String.format("%02X ", b & 0xFF));
        }
        return encodedName.toString().replace(" ", "").toLowerCase();
    }

}
