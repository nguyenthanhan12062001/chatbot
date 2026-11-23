package com.chatai.an.ai.integration.adb;

import android.content.Context;

import androidx.annotation.NonNull;

import com.chatai.an.core.AppLogger;

import java.io.IOException;

/**
 * Compatibility facade over AdbKeyPair, AdbPairingClient, and AdbProtocolClient.
 */
public final class EmbeddedAdbManager {
    private static final String TAG = "EmbeddedAdbManager";

    public static class AdbPairingResult {
        public final boolean success;
        public final String message;

        public AdbPairingResult(boolean success, String message) {
            this.success = success;
            this.message = message != null ? message : "";
        }
    }

    public static class AdbSession {
        private final AdbProtocolClient client;
        private final boolean connected;

        public AdbSession(AdbProtocolClient client, boolean connected) {
            this.client = client;
            this.connected = connected;
        }

        public boolean isConnected() {
            return connected && client != null && client.isConnected();
        }

        public void close() {
            if (client != null) {
                client.close();
            }
        }
    }

    private final AdbKeyPair keyPair;
    private final AdbPairingClient pairingClient;

    public EmbeddedAdbManager(@NonNull Context context) {
        this.keyPair = new AdbKeyPair(context.getApplicationContext());
        this.pairingClient = new AdbPairingClient(this.keyPair);
    }

    public boolean hasSavedCredentials() {
        return keyPair.hasKeyPair();
    }

    public AdbPairingResult pairWithCode(@NonNull String pairingHost, int pairingPort, @NonNull String pairingCode) {
        AdbPairingClient.PairingOutcome outcome = pairingClient.pair(pairingHost, pairingPort, pairingCode);
        return new AdbPairingResult(outcome.success, outcome.message);
    }

    public AdbSession connect(@NonNull String host, int port) {
        AdbProtocolClient client = new AdbProtocolClient(keyPair);
        try {
            client.connect(host, port, 8_000);
            return new AdbSession(client, true);
        } catch (Exception e) {
            AppLogger.e(TAG, "ADB Connection failed", e);
            client.close();
            return new AdbSession(null, false);
        }
    }

    public String executeShellCommand(@NonNull AdbSession session, @NonNull String command, int timeoutSec) {
        if (!session.isConnected() || session.client == null) {
            return "Error: ADB session not connected.";
        }
        try {
            AdbProtocolClient.ShellOutput out = session.client.executeShell(command, timeoutSec * 1000);
            return out.stdout != null && !out.stdout.isEmpty() ? out.stdout : out.stderr;
        } catch (IOException e) {
            return "Embedded ADB shell error: " + e.getMessage();
        }
    }
}
