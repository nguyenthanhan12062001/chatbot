package com.chatai.an.ai.integration.adb;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Manages persistent RSA-2048 keypair, self-signed X.509 certificate,
 * AOSP ADB 524-byte RSAPublicKey binary encoding, and ADB AUTH token signing.
 */
public final class AdbKeyPair {

    private static final int KEY_SIZE_BITS = 2048;
    private static final int ANDROID_PUBKEY_MODULUS_SIZE = 256;
    private static final int ANDROID_PUBKEY_MODULUS_WORDS = 64;
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final File keyFile;
    private final File certFile;

    private volatile PrivateKey privateKey;
    private volatile X509Certificate certificate;

    public AdbKeyPair(@NonNull Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "adb");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        this.keyFile = new File(dir, "adb_key.pk8");
        this.certFile = new File(dir, "adb_cert.der");
    }

    public synchronized boolean hasKeyPair() {
        return keyFile.exists() && keyFile.length() > 0 && certFile.exists() && certFile.length() > 0;
    }

    public synchronized void ensureLoadedOrCreated() throws Exception {
        if (privateKey != null && certificate != null) {
            return;
        }
        if (hasKeyPair()) {
            try {
                byte[] pkcs8 = readAll(keyFile);
                byte[] certDer = readAll(certFile);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey loadedKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate loadedCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
                if (loadedKey != null && loadedCert != null) {
                    this.privateKey = loadedKey;
                    this.certificate = loadedCert;
                    return;
                }
            } catch (Exception ignored) {
                // Regenerate corrupted files below
            }
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(KEY_SIZE_BITS, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = generateSelfSignedCertificate(kp);

        writeAll(keyFile, kp.getPrivate().getEncoded());
        writeAll(certFile, cert.getEncoded());
        this.privateKey = kp.getPrivate();
        this.certificate = cert;
    }

    public SSLContext createSslContext() throws Exception {
        ensureLoadedOrCreated();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("adb", privateKey, new char[0], new Certificate[]{certificate});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };

        SSLContext ctx;
        try {
            ctx = SSLContext.getInstance("TLSv1.3");
        } catch (Exception e) {
            ctx = SSLContext.getInstance("TLSv1.2");
        }
        ctx.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
        return ctx;
    }

    /**
     * Builds AOSP ADB RSAPublicKey packet payload: "<base64> chatai@android\0".
     */
    public byte[] getAdbPublicKeyPayload() throws Exception {
        ensureLoadedOrCreated();
        RSAPublicKey rsaPub = (RSAPublicKey) certificate.getPublicKey();
        byte[] binaryStruct = encodeAndroidRsaPublicKey(rsaPub);
        String b64 = Base64.encodeToString(binaryStruct, Base64.NO_WRAP);
        String full = b64 + " chatai@android\0";
        return full.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Signs 20-byte ADB AUTH token using PKCS#1 v1.5 SHA-1 DigestInfo.
     */
    public byte[] signAuthToken(@NonNull byte[] token) throws Exception {
        ensureLoadedOrCreated();
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.length, token.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, privateKey);
        return cipher.doFinal(digestInfo);
    }

    private static byte[] encodeAndroidRsaPublicKey(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger r = BigInteger.ONE.shiftLeft(KEY_SIZE_BITS);
        BigInteger rr = r.multiply(r).mod(n);

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + ANDROID_PUBKEY_MODULUS_SIZE + ANDROID_PUBKEY_MODULUS_SIZE + 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(ANDROID_PUBKEY_MODULUS_WORDS);
        buf.putInt(n0inv.intValue());
        buf.put(toLittleEndian256(n));
        buf.put(toLittleEndian256(rr));
        buf.putInt(pub.getPublicExponent().intValue());
        return buf.array();
    }

    private static byte[] toLittleEndian256(BigInteger value) {
        byte[] be = value.toByteArray();
        byte[] out = new byte[ANDROID_PUBKEY_MODULUS_SIZE];
        int srcPos = be[0] == 0 ? 1 : 0;
        int len = Math.min(be.length - srcPos, ANDROID_PUBKEY_MODULUS_SIZE);
        for (int i = 0; i < len; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static X509Certificate generateSelfSignedCertificate(KeyPair kp) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 86_400_000L);
        Date notAfter = new Date(now + 10L * 365L * 86_400_000L);

        byte[] subject = buildNameCn("ChatAI ADB");
        byte[] serial = new byte[]{0x02, 0x01, 0x01}; // INTEGER 1
        byte[] version = derExplicit(0, new byte[]{0x02, 0x01, 0x02}); // v3 (2)
        byte[] sigAlg = derSequence(concat(
                new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b}, // sha256WithRSAEncryption
                new byte[]{0x05, 0x00}
        ));
        byte[] validity = derSequence(concat(derUtcTime(notBefore), derUtcTime(notAfter)));
        byte[] spki = kp.getPublic().getEncoded();

        byte[] tbsCertificate = derSequence(concat(
                version,
                serial,
                sigAlg,
                subject,
                validity,
                subject,
                spki
        ));

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbsCertificate);
        byte[] signatureBytes = sig.sign();

        byte[] bitString = new byte[1 + signatureBytes.length];
        bitString[0] = 0x00;
        System.arraycopy(signatureBytes, 0, bitString, 1, signatureBytes.length);

        byte[] certDer = derSequence(concat(
                tbsCertificate,
                sigAlg,
                derTag(0x03, bitString)
        ));

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
    }

    private static byte[] buildNameCn(String cn) {
        byte[] oidCn = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03}; // 2.5.4.3
        byte[] val = derTag(0x0c, cn.getBytes(StandardCharsets.UTF_8));
        byte[] attr = derSequence(concat(oidCn, val));
        byte[] set = derTag(0x31, attr);
        return derSequence(set);
    }

    private static byte[] derUtcTime(Date d) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return derTag(0x17, fmt.format(d).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derSequence(byte[] content) {
        return derTag(0x30, content);
    }

    private static byte[] derExplicit(int tagNo, byte[] content) {
        return derTag(0xA0 | tagNo, content);
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else if (len < 65536) {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }
}
