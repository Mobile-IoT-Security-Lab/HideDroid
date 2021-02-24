package apktool.module;

import android.content.Context;
import android.content.res.AssetManager;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

import brut.util.Logger;

public class SignatureManager {
    private static PrivateKey privateKey;
    private static X509Certificate certificate;

    private Logger logger;

    public SignatureManager(Context applicationContext, Logger logger) throws Exception {
        if (SignatureManager.privateKey == null || SignatureManager.certificate == null) {
            this.loadSignatureData(applicationContext.getAssets());
        }
        this.logger = logger;
    }

    private void loadSignatureData(AssetManager assets) throws Exception {
        try (InputStream cert = assets.open("key/testkey.x509.pem")) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(cert);
        }

        try (InputStream key = assets.open("key/testkey.pk8")) {
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(IOUtils.toByteArray(key));
            privateKey = KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm()).generatePrivate(keySpec);
        }
    }

    public void sign(final File inputApk, final File outputApk, int minSdk) throws Exception {
        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "CERT", privateKey, Collections.singletonList(certificate)).build();
        ApkSigner.Builder builder = new ApkSigner.Builder(Collections.singletonList(signerConfig));
        builder.setInputApk(inputApk);
        builder.setOutputApk(outputApk);
        builder.setCreatedBy("Apktool");
        builder.setMinSdkVersion(minSdk);
        builder.setV1SigningEnabled(true);
        builder.setV2SigningEnabled(true);
        ApkSigner signer = builder.build();
        this.logger.info(String.format("Signing apk %s", inputApk));
        signer.sign();
        this.logger.info(String.format("Signed apk saved in %s", outputApk));
    }

    public boolean verify(File apk) {
        ApkVerifier.Builder builder = new ApkVerifier.Builder(apk);
        ApkVerifier verifier = builder.build();
        try {
            ApkVerifier.Result result = verifier.verify();
            this.logger.info(String.format("Verifying signature of apk %s", apk));
            boolean isVerified = result.isVerified();
            if (isVerified) {
                if (result.isVerifiedUsingV1Scheme())
                    this.logger.info("V1 signature verification succeeded");
                else
                    this.logger.warning("V1 signature verification failed");
                if (result.isVerifiedUsingV2Scheme())
                    this.logger.info("V2 signature verification succeeded");
                else
                    this.logger.warning("V2 signature verification failed");
                int i = 0;
                List<X509Certificate> signerCertificates = result.getSignerCertificates();
                this.logger.info(String.format("Number of signatures: %s", signerCertificates.size()));
                for (X509Certificate logCert : signerCertificates) {
                    i++;
                    logCert(logCert, "Signature" + i);
                }
            }
            for (ApkVerifier.IssueWithParams warn : result.getWarnings()) {
                this.logger.warning(warn.toString());
            }
            for (ApkVerifier.IssueWithParams err : result.getErrors()) {
                this.logger.error(err.toString());
            }
            for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeIgnoredSigners()) {
                String name = signer.getName();
                for (ApkVerifier.IssueWithParams err : signer.getErrors()) {
                    this.logger.error(String.format("JAR signer %s: %s", name, err));
                }
                for (ApkVerifier.IssueWithParams err : signer.getWarnings()) {
                    this.logger.warning(String.format("JAR signer %s: %s", name, err));
                }
            }
            return isVerified;
        } catch (Exception e) {
            this.logger.log(Level.WARNING, "Verification failed!", e);
            return false;
        }
    }

    public void logCert(X509Certificate x509Certificate, CharSequence charSequence) {
        int bitLength;
        Principal subjectDN = x509Certificate.getSubjectDN();
        this.logger.info(String.format("%s Distinguished name: %s", charSequence, subjectDN));
        PublicKey publicKey = x509Certificate.getPublicKey();
        if (publicKey instanceof RSAKey) {
            bitLength = ((RSAKey) publicKey).getModulus().bitLength();
        } else if (publicKey instanceof ECKey) {
            bitLength = ((ECKey) publicKey).getParams().getOrder().bitLength();
        } else {
            bitLength = -1;
        }
        this.logger.info(String.format("%s Key size (bits): %s",
                charSequence, bitLength != -1 ? String.valueOf(bitLength) : "Unknown"));
        logKey(publicKey, charSequence, this.logger);
    }

    public static void logKey(Key key, CharSequence charSequence, Logger apktoolLog) {
        String algorithm = key.getAlgorithm();
        apktoolLog.info(String.format("%s Key algorithm: %s", charSequence, algorithm));
    }
}
