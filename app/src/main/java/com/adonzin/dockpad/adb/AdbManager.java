package com.adonzin.dockpad.adb;

import android.content.Context;
import android.os.Build;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** DockPad's own ADB client identity (a key pair + certificate kept in the app's private storage). */
public final class AdbManager extends AbsAdbConnectionManager {
    private static AdbManager instance;

    public static synchronized AdbManager get(Context context) throws Exception {
        if (instance == null) instance = new AdbManager(context.getApplicationContext());
        return instance;
    }

    private final PrivateKey privateKey;
    private final Certificate certificate;

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        File keyFile = new File(context.getFilesDir(), "adb_private.key");
        File certFile = new File(context.getFilesDir(), "adb_cert.der");
        PrivateKey key = null;
        Certificate cert = null;
        if (keyFile.exists() && certFile.exists()) {
            try {
                key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(keyFile)));
                cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readAll(certFile)));
            } catch (Exception e) {
                key = null;
                cert = null;
            }
        }
        if (key == null || cert == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair pair = gen.generateKeyPair();
            PublicKey publicKey = pair.getPublic();
            key = pair.getPrivate();
            String alg = "SHA512withRSA";
            Date notBefore = new Date();
            Date notAfter = new Date(System.currentTimeMillis() + 3650L * 86400000L);
            CertificateExtensions ext = new CertificateExtensions();
            ext.set("SubjectKeyIdentifier", new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
            ext.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
            X500Name name = new X500Name("CN=DockPad");
            X509CertInfo info = new X509CertInfo();
            info.set("version", new CertificateVersion(2));
            info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
            info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(alg)));
            info.set("subject", new CertificateSubjectName(name));
            info.set("key", new CertificateX509Key(publicKey));
            info.set("validity", new CertificateValidity(notBefore, notAfter));
            info.set("issuer", new CertificateIssuerName(name));
            info.set("extensions", ext);
            X509CertImpl impl = new X509CertImpl(info);
            impl.sign(key, alg);
            cert = impl;
            try (FileOutputStream o = new FileOutputStream(keyFile)) {
                o.write(key.getEncoded());
            }
            try (FileOutputStream o = new FileOutputStream(certFile)) {
                o.write(cert.getEncoded());
            }
        }
        privateKey = key;
        certificate = cert;
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return b;
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "DockPad";
    }
}
