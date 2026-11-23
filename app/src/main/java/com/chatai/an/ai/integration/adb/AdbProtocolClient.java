package com.chatai.an.ai.integration.adb;

import androidx.annotation.NonNull;

import com.chatai.an.core.AppLogger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Implements the binary ADB wire protocol over TLS (_adb-tls-connect._tcp)
 * for connecting to Android 11+ Wireless Debugging and executing shell commands under UID 2000.
 */
public final class AdbProtocolClient implements Closeable {

    private static final String TAG = "AdbProtocolClient";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private static final int ADB_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 1024 * 1024;

    private static final int SHELL_V2_ID_STDOUT = 1;
    private static final int SHELL_V2_ID_STDERR = 2;
    private static final int SHELL_V2_ID_EXIT = 3;

    public static final class ShellOutput {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        public ShellOutput(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }
    }

    private final AdbKeyPair keyPair;
    private final AtomicInteger localIdSeq = new AtomicInteger(1);
    private SSLSocket socket;
    private DataInputStream dis;
    private OutputStream os;
    private boolean supportsShellV2 = false;

    public AdbProtocolClient(@NonNull AdbKeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public synchronized void connect(@NonNull String host, int port, int timeoutMs) throws Exception {
        close();
        keyPair.ensureLoadedOrCreated();
        SSLContext sslContext = keyPair.createSslContext();

        SSLSocket sock = (SSLSocket) sslContext.getSocketFactory().createSocket();
        sock.setTcpNoDelay(true);
        sock.setSoTimeout(timeoutMs);
        sock.connect(new InetSocketAddress(host, port), timeoutMs);
        sock.startHandshake();

        this.socket = sock;
        this.dis = new DataInputStream(sock.getInputStream());
        this.os = sock.getOutputStream();

        byte[] banner = "host::features=shell_v2,cmd,stat_v2\0".getBytes(StandardCharsets.UTF_8);
        sendPacket(A_CNXN, ADB_VERSION, MAX_PAYLOAD, banner);

        boolean sentSig = false;
        while (true) {
            AdbPacket pkt = readPacket();
            if (pkt.command == A_CNXN) {
                String deviceBanner = new String(pkt.payload, StandardCharsets.UTF_8);
                this.supportsShellV2 = deviceBanner.contains("shell_v2");
                AppLogger.i(TAG, "ADB TLS connected: " + deviceBanner.replace("\0", ""));
                return;
            } else if (pkt.command == A_AUTH && pkt.arg0 == ADB_AUTH_TOKEN) {
                if (!sentSig) {
                    sentSig = true;
                    byte[] sig = keyPair.signAuthToken(pkt.payload);
                    sendPacket(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig);
                } else {
                    byte[] pub = keyPair.getAdbPublicKeyPayload();
                    sendPacket(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pub);
                }
            } else {
                throw new IOException("Unexpected ADB handshake packet: 0x" + Integer.toHexString(pkt.command));
            }
        }
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized ShellOutput executeShell(@NonNull String command, int timeoutMs) throws IOException {
        if (!isConnected()) {
            throw new IOException("ADB client is not connected.");
        }
        socket.setSoTimeout(timeoutMs);

        int localId = localIdSeq.getAndIncrement();
        boolean useV2 = supportsShellV2;
        String service = (useV2 ? "shell,v2,raw:" : "shell:") + command + "\0";
        sendPacket(A_OPEN, localId, 0, service.getBytes(StandardCharsets.UTF_8));

        int remoteId = -1;
        ByteArrayOutputStream stdoutBos = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBos = new ByteArrayOutputStream();
        ByteArrayOutputStream v2Accum = new ByteArrayOutputStream();
        int exitCode = 0;

        while (true) {
            AdbPacket pkt = readPacket();
            if (pkt.command == A_OKAY && pkt.arg1 == localId) {
                remoteId = pkt.arg0;
            } else if (pkt.command == A_WRTE && pkt.arg1 == localId) {
                if (remoteId == -1) remoteId = pkt.arg0;
                sendPacket(A_OKAY, localId, remoteId, new byte[0]);
                if (useV2) {
                    v2Accum.write(pkt.payload);
                    byte[] buf = v2Accum.toByteArray();
                    int pos = 0;
                    while (pos + 5 <= buf.length) {
                        int streamId = buf[pos] & 0xFF;
                        int len = ByteBuffer.wrap(buf, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        if (len < 0 || pos + 5 + len > buf.length) {
                            break;
                        }
                        if (streamId == SHELL_V2_ID_STDOUT) {
                            stdoutBos.write(buf, pos + 5, len);
                        } else if (streamId == SHELL_V2_ID_STDERR) {
                            stderrBos.write(buf, pos + 5, len);
                        } else if (streamId == SHELL_V2_ID_EXIT && len >= 1) {
                            exitCode = buf[pos + 5] & 0xFF;
                        }
                        pos += 5 + len;
                    }
                    v2Accum.reset();
                    if (pos < buf.length) {
                        v2Accum.write(buf, pos, buf.length - pos);
                    }
                } else {
                    stdoutBos.write(pkt.payload);
                }
            } else if (pkt.command == A_CLSE && pkt.arg1 == localId) {
                if (remoteId != -1) {
                    try {
                        sendPacket(A_CLSE, localId, remoteId, new byte[0]);
                    } catch (IOException ignored) {
                    }
                }
                break;
            }
        }

        return new ShellOutput(
                exitCode,
                stdoutBos.toString(StandardCharsets.UTF_8.name()),
                stderrBos.toString(StandardCharsets.UTF_8.name())
        );
    }

    private void sendPacket(int command, int arg0, int arg1, byte[] payload) throws IOException {
        byte[] data = payload != null ? payload : new byte[0];
        int checksum = 0;
        for (byte b : data) {
            checksum += (b & 0xFF);
        }
        ByteBuffer hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(command);
        hdr.putInt(arg0);
        hdr.putInt(arg1);
        hdr.putInt(data.length);
        hdr.putInt(checksum);
        hdr.putInt(command ^ 0xFFFFFFFF);
        os.write(hdr.array());
        if (data.length > 0) {
            os.write(data);
        }
        os.flush();
    }

    private AdbPacket readPacket() throws IOException {
        byte[] hdrBytes = new byte[24];
        dis.readFully(hdrBytes);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
        int command = hdr.getInt();
        int arg0 = hdr.getInt();
        int arg1 = hdr.getInt();
        int len = hdr.getInt();
        hdr.getInt(); // crc32
        int magic = hdr.getInt();
        if (magic != (command ^ 0xFFFFFFFF)) {
            throw new IOException("Invalid ADB packet magic.");
        }
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("Invalid ADB payload size: " + len);
        }
        byte[] payload = new byte[len];
        if (len > 0) {
            dis.readFully(payload);
        }
        return new AdbPacket(command, arg0, arg1, payload);
    }

    @Override
    public synchronized void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        dis = null;
        os = null;
    }

    private static final class AdbPacket {
        final int command;
        final int arg0;
        final int arg1;
        final byte[] payload;

        AdbPacket(int command, int arg0, int arg1, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.payload = payload;
        }
    }
}
