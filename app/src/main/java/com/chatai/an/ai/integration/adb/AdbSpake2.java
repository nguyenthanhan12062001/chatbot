package com.chatai.an.ai.integration.adb;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure Java BoringSSL-compatible SPAKE2 over Ed25519 + HKDF-SHA256 + AES-128-GCM
 * matching AOSP adb/pairing_auth for Android 11+ Wireless Debugging pairing.
 */
public final class AdbSpake2 {

    // Curve field p = 2^255 - 19
    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    // Subgroup order L = 2^252 + 27742317777372353535851937790883648493
    private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    // Curve constant d = -121665 / 121666 mod p
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P))
            .mod(P);
    // sqrt(-1) mod p = 2^((p-1)/4) mod p
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    // Ed25519 base point B
    private static final Point B = decodePoint(hexToBytes(
            "5866666666666666666666666666666666666666666666666666666666666666"
    ));

    // BoringSSL SPAKE2 fixed points M and N on Ed25519
    private static final Point M = decodePoint(hexToBytes(
            "d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf"
    ));
    private static final Point N = decodePoint(hexToBytes(
            "d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab"
    ));

    private static final byte[] CLIENT_NAME = "adb pair client\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SERVER_NAME = "adb pair server\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HKDF_INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII);

    private static final class Point {
        final BigInteger x;
        final BigInteger y;

        Point(BigInteger x, BigInteger y) {
            this.x = x.mod(P);
            this.y = y.mod(P);
        }

        Point negate() {
            return new Point(P.subtract(x).mod(P), y);
        }

        Point add(Point q) {
            BigInteger x1x2 = x.multiply(q.x).mod(P);
            BigInteger y1y2 = y.multiply(q.y).mod(P);
            BigInteger dx1x2y1y2 = D.multiply(x1x2).multiply(y1y2).mod(P);

            BigInteger numX = x.multiply(q.y).add(y.multiply(q.x)).mod(P);
            BigInteger denX = BigInteger.ONE.add(dx1x2y1y2).mod(P);
            BigInteger rx = numX.multiply(denX.modInverse(P)).mod(P);

            BigInteger numY = y1y2.add(x1x2).mod(P);
            BigInteger denY = BigInteger.ONE.subtract(dx1x2y1y2).mod(P);
            BigInteger ry = numY.multiply(denY.modInverse(P)).mod(P);
            return new Point(rx, ry);
        }

        Point subtract(Point q) {
            return add(q.negate());
        }

        Point scalarMult(BigInteger scalar) {
            Point acc = new Point(BigInteger.ZERO, BigInteger.ONE);
            Point cur = this;
            int bitLen = scalar.bitLength();
            for (int i = 0; i < bitLen; i++) {
                if (scalar.testBit(i)) {
                    acc = acc.add(cur);
                }
                cur = cur.add(cur);
            }
            return acc;
        }

        byte[] encode() {
            byte[] out = toLittleEndian32(y);
            if (x.testBit(0)) {
                out[31] |= (byte) 0x80;
            } else {
                out[31] &= (byte) 0x7F;
            }
            return out;
        }
    }

    private final byte[] passwordHash;
    private final BigInteger passwordScalar;
    private final BigInteger privateScalar;
    private byte[] myMsg;
    private SecretKeySpec aesKey;
    private long encSeq = 0;
    private long decSeq = 0;

    public AdbSpake2(byte[] passwordBytes) throws Exception {
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        this.passwordHash = sha512.digest(passwordBytes);
        this.passwordScalar = fromLittleEndian(passwordHash).mod(L);

        // BoringSSL spake25519.cc generates private_tmp in [1, L) then shifts left by 3 (multiplies by cofactor 8)
        // so that privateScalar clears any small-subgroup component on peer's point.
        SecureRandom sr = new SecureRandom();
        BigInteger rawPriv;
        do {
            byte[] rnd = new byte[32];
            sr.nextBytes(rnd);
            rnd[31] &= 0x0F;
            rawPriv = fromLittleEndian(rnd);
        } while (rawPriv.equals(BigInteger.ZERO) || rawPriv.compareTo(L) >= 0);
        this.privateScalar = rawPriv.shiftLeft(3);
    }

    public byte[] generateMessage() {
        // Alice: X = x * B + w * M
        Point xB = B.scalarMult(privateScalar);
        Point wM = M.scalarMult(passwordScalar);
        this.myMsg = xB.add(wM).encode();
        return Arrays.copyOf(myMsg, myMsg.length);
    }

    public void processPeerMessage(byte[] theirMsg) throws Exception {
        if (myMsg == null) {
            throw new IllegalStateException("generateMessage() must be called before processPeerMessage()");
        }
        if (theirMsg == null || theirMsg.length != 32) {
            throw new IllegalArgumentException("Invalid SPAKE2 peer message length");
        }
        Point yPoint = decodePoint(theirMsg);
        Point wN = N.scalarMult(passwordScalar);
        // Z = x * (Y - w * N). Since privateScalar has bottom 3 bits cleared, cofactor 8 is already cleared.
        Point kPoint = yPoint.subtract(wN).scalarMult(privateScalar);
        byte[] kBytes = kPoint.encode();

        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        updateWithLengthPrefix(sha512, CLIENT_NAME);
        updateWithLengthPrefix(sha512, SERVER_NAME);
        updateWithLengthPrefix(sha512, myMsg);
        updateWithLengthPrefix(sha512, theirMsg);
        updateWithLengthPrefix(sha512, kBytes);
        updateWithLengthPrefix(sha512, passwordHash);

        byte[] sharedKey = sha512.digest();
        byte[] aesBytes = hkdfSha256(sharedKey, HKDF_INFO, 16);
        this.aesKey = new SecretKeySpec(aesBytes, "AES");
    }

    public byte[] encrypt(byte[] plaintext) throws Exception {
        if (aesKey == null) throw new IllegalStateException("SPAKE2 handshake not completed");
        byte[] nonce = buildNonce(encSeq++);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(plaintext);
    }

    public byte[] decrypt(byte[] ciphertext) throws Exception {
        if (aesKey == null) throw new IllegalStateException("SPAKE2 handshake not completed");
        byte[] nonce = buildNonce(decSeq++);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] buildNonce(long seq) {
        ByteBuffer bb = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        bb.putLong(seq);
        bb.putInt(0);
        return bb.array();
    }

    private static void updateWithLengthPrefix(MessageDigest md, byte[] data) {
        ByteBuffer lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        lenBuf.putLong(data.length);
        md.update(lenBuf.array());
        md.update(data);
    }

    private static byte[] hkdfSha256(byte[] ikm, byte[] info, int outLen) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        ByteArrayOutputStream okm = new ByteArrayOutputStream();
        byte[] prev = new byte[0];
        int counter = 1;
        while (okm.size() < outLen) {
            mac.reset();
            mac.update(prev);
            mac.update(info);
            mac.update((byte) counter++);
            prev = mac.doFinal();
            okm.write(prev, 0, Math.min(prev.length, outLen - okm.size()));
        }
        return okm.toByteArray();
    }

    private static Point decodePoint(byte[] enc) {
        byte[] copy = Arrays.copyOf(enc, 32);
        int xSign = (copy[31] & 0x80) != 0 ? 1 : 0;
        copy[31] &= 0x7F;
        BigInteger y = fromLittleEndian(copy);
        if (y.compareTo(P) >= 0) {
            throw new IllegalArgumentException("Invalid Ed25519 y-coordinate");
        }
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        // x^2 = u / v mod p; Ed25519 candidate x = u * v^3 * (u * v^7)^((p-5)/8) mod p
        BigInteger v3 = v.pow(3).mod(P);
        BigInteger v7 = v3.multiply(v3).multiply(v).mod(P);
        BigInteger uv7 = u.multiply(v7).mod(P);
        BigInteger exp = P.subtract(BigInteger.valueOf(5)).shiftRight(3);
        BigInteger x = u.multiply(v3).multiply(uv7.modPow(exp, P)).mod(P);

        BigInteger check = v.multiply(x.multiply(x).mod(P)).mod(P);
        if (!check.equals(u)) {
            x = x.multiply(SQRT_M1).mod(P);
            check = v.multiply(x.multiply(x).mod(P)).mod(P);
            if (!check.equals(u)) {
                throw new IllegalArgumentException("Point is not on Ed25519 curve");
            }
        }
        if ((x.testBit(0) ? 1 : 0) != xSign) {
            x = P.subtract(x).mod(P);
        }
        return new Point(x, y);
    }

    private static BigInteger fromLittleEndian(byte[] le) {
        byte[] be = new byte[le.length + 1];
        for (int i = 0; i < le.length; i++) {
            be[le.length - i] = le[i];
        }
        return new BigInteger(1, be);
    }

    private static byte[] toLittleEndian32(BigInteger val) {
        byte[] be = val.toByteArray();
        byte[] out = new byte[32];
        int start = be[0] == 0 ? 1 : 0;
        int len = Math.min(32, be.length - start);
        for (int i = 0; i < len; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }
}
