package com.chatai.an.ai.integration.adb;

import android.os.Build;

import androidx.annotation.NonNull;

import com.chatai.an.core.AppLogger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Implements Android 11+ Wireless Debugging TLS + SPAKE2 pairing client (_adb-tls-pairing._tcp).
 */
public final class AdbPairingClient {

    private static final String TAG = "AdbPairingClient";
    private static final byte PAIRING_PACKET_VERSION = 1;
    private static final byte PACKET_TYPE_SPAKE2_MSG = 0;
    private static final byte PACKET_TYPE_PEER_INFO = 1;
    private static final int PEER_INFO_SIZE = 8192;
    private static final byte PEER_INFO_RSA_PUB_KEY = 0;
    // AOSP tls_connection.cpp passes sizeof(kExportedKeyLabel) = 10 bytes including trailing NUL.
    public static final String EXPORTED_KEY_LABEL = "adb-label\u0000";
    public static final int EXPORTED_KEY_SIZE = 64;

    private final AdbKeyPair keyPair;

    public AdbPairingClient(@NonNull AdbKeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static final class PairingOutcome {
        public final boolean success;
        public final String message;

        public PairingOutcome(boolean success, String message) {
            this.success = success;
            this.message = message != null ? message : "";
        }
    }

    public PairingOutcome pair(@NonNull String host, int port, @NonNull String rawPin) {
        String pin = rawPin.replaceAll("\\s+", "");
        if (!pin.matches("\\d{6}")) {
            return new PairingOutcome(false, "Mã PIN ghép nối phải gồm đúng 6 chữ số.");
        }

        try {
            keyPair.ensureLoadedOrCreated();
            SSLContext sslContext = keyPair.createSslContext();

            try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket()) {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(10_000);
                socket.connect(new InetSocketAddress(host, port), 8_000);
                socket.startHandshake();

                AppLogger.d(TAG, "TLS pairing connected to " + host + ":" + port
                        + " protocol=" + socket.getSession().getProtocol());

                byte[] keyingMaterial = exportKeyingMaterial(socket);
                if (keyingMaterial.length != EXPORTED_KEY_SIZE) {
                    throw new IOException("Unexpected exported keying material length: " + keyingMaterial.length);
                }
                byte[] pinBytes = pin.getBytes(StandardCharsets.UTF_8);
                byte[] password = new byte[pinBytes.length + keyingMaterial.length];
                System.arraycopy(pinBytes, 0, password, 0, pinBytes.length);
                System.arraycopy(keyingMaterial, 0, password, pinBytes.length, keyingMaterial.length);

                AdbSpake2 spake2 = new AdbSpake2(password);
                byte[] ourSpakeMsg = spake2.generateMessage();

                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                DataInputStream dis = new DataInputStream(socket.getInputStream());

                // 1. Exchange SPAKE2 messages
                writePacket(dos, PACKET_TYPE_SPAKE2_MSG, ourSpakeMsg);
                Packet peerSpakePacket = readPacket(dis, 1024);
                if (peerSpakePacket.type != PACKET_TYPE_SPAKE2_MSG) {
                    return new PairingOutcome(false, "Unexpected pairing packet type: " + peerSpakePacket.type);
                }
                spake2.processPeerMessage(peerSpakePacket.payload);

                // 2. Build 8192-byte PeerInfo with our ADB RSA public key
                byte[] pubKeyPayload = keyPair.getAdbPublicKeyPayload();
                byte[] peerInfo = new byte[PEER_INFO_SIZE];
                peerInfo[0] = PEER_INFO_RSA_PUB_KEY;
                int copyLen = Math.min(pubKeyPayload.length, PEER_INFO_SIZE - 1);
                System.arraycopy(pubKeyPayload, 0, peerInfo, 1, copyLen);

                byte[] encryptedPeerInfo = spake2.encrypt(peerInfo);
                writePacket(dos, PACKET_TYPE_PEER_INFO, encryptedPeerInfo);

                // 3. Read & decrypt server's PeerInfo to verify mutual authentication
                Packet serverPeerPacket = readPacket(dis, PEER_INFO_SIZE + 256);
                if (serverPeerPacket.type != PACKET_TYPE_PEER_INFO) {
                    return new PairingOutcome(false, "Expected PeerInfo from adbd.");
                }
                byte[] decryptedServerPeer = spake2.decrypt(serverPeerPacket.payload);
                if (decryptedServerPeer.length == 0) {
                    return new PairingOutcome(false, "Empty server PeerInfo response.");
                }

                AppLogger.i(TAG, "Wireless Debugging pairing succeeded with " + host + ":" + port);
                return new PairingOutcome(true, "Pairing successful");
            }
        } catch (javax.crypto.AEADBadTagException badTag) {
            AppLogger.w(TAG, "SPAKE2 authentication failed (wrong pairing code) for " + host + ":" + port);
            return new PairingOutcome(false, "Sai mã PIN ghép nối hoặc phiên ghép nối đã hết hạn.");
        } catch (Exception e) {
            AppLogger.e(TAG, "Pairing failed for " + host + ":" + port, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new PairingOutcome(false, "Ghép nối thất bại: " + msg);
        }
    }

    private static byte[] exportKeyingMaterial(SSLSocket socket) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Class<?> sslSockets = Class.forName("android.net.ssl.SSLSockets");
                Method m = sslSockets.getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class);
                Object res = m.invoke(null, socket, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE);
                if (res instanceof byte[]) {
                    return (byte[]) res;
                }
            } catch (Throwable t) {
                AppLogger.w(TAG, "SSLSockets.exportKeyingMaterial failed, trying fallback: " + t.getMessage());
            }
        }
        try {
            Method m = socket.getClass().getMethod("exportKeyingMaterial", String.class, byte[].class, int.class);
            Object res = m.invoke(socket, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE);
            if (res instanceof byte[]) {
                return (byte[]) res;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt");
            Method m = conscrypt.getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class);
            Object res = m.invoke(null, socket, EXPORTED_KEY_LABEL, null, EXPORTED_KEY_SIZE);
            if (res instanceof byte[]) {
                return (byte[]) res;
            }
        } catch (Throwable t) {
            throw new IOException("TLS exportKeyingMaterial unavailable on this device: " + t.getMessage(), t);
        }
        throw new IOException("TLS exportKeyingMaterial returned null.");
    }

    private static void writePacket(DataOutputStream dos, byte type, byte[] payload) throws IOException {
        ByteBuffer hdr = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
        hdr.put(PAIRING_PACKET_VERSION);
        hdr.put(type);
        hdr.putInt(payload.length);
        dos.write(hdr.array());
        dos.write(payload);
        dos.flush();
    }

    private static Packet readPacket(DataInputStream dis, int maxLen) throws IOException {
        byte[] hdrBytes = new byte[6];
        dis.readFully(hdrBytes);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.BIG_ENDIAN);
        byte version = hdr.get();
        byte type = hdr.get();
        int len = hdr.getInt();
        if (version != PAIRING_PACKET_VERSION) {
            throw new IOException("Unsupported pairing packet version: " + version);
        }
        if (len < 0 || len > maxLen) {
            throw new IOException("Invalid pairing payload length: " + len);
        }
        byte[] payload = new byte[len];
        dis.readFully(payload);
        return new Packet(type, payload);
    }

    private static final class Packet {
        final byte type;
        final byte[] payload;

        Packet(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
