package com.chatai.an;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.content.Intent;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.chatai.an.ai.integration.adb.AdbKeyPair;
import com.chatai.an.ai.integration.adb.AdbPairingClient;
import com.chatai.an.ai.integration.adb.AdbSpake2;
import com.chatai.an.ai.integration.adb.ChatAiServerManager;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.presentation.settings.SettingsActivity;
import com.chatai.an.presentation.settings.WirelessAdbStarterActivity;
import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = ChatAiApplication.class)
public class WirelessAdbAndChatAiServerTest {

    @Test
    public void adbKeyPair_generatesPersistentKeys524ByteStructValidSslAndAuthSignature() throws Exception {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        AdbKeyPair keyPair = new AdbKeyPair(app);
        keyPair.ensureLoadedOrCreated();
        assertTrue(keyPair.hasKeyPair());
        assertNotNull(keyPair.createSslContext());

        byte[] payload = keyPair.getAdbPublicKeyPayload();
        String payloadStr = new String(payload, StandardCharsets.UTF_8);
        assertTrue(payloadStr.endsWith(" chatai@android\0"));
        String b64 = payloadStr.substring(0, payloadStr.indexOf(' '));
        byte[] struct = Base64.decode(b64, Base64.NO_WRAP);
        assertEquals(524, struct.length);

        ByteBuffer bb = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);
        int modulusWords = bb.getInt();
        bb.getInt(); // n0inv
        byte[] nLe = new byte[256];
        bb.get(nLe);
        byte[] rrLe = new byte[256];
        bb.get(rrLe);
        int exponent = bb.getInt();
        assertEquals(64, modulusWords);
        assertEquals(65537, exponent);

        // Reconstruct BigInteger modulus from LE bytes and verify signed 20-byte ADB AUTH token
        byte[] nBe = new byte[257];
        for (int i = 0; i < 256; i++) {
            nBe[256 - i] = nLe[i];
        }
        RSAPublicKey reconstructedPub = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(new BigInteger(1, nBe), BigInteger.valueOf(exponent)));

        byte[] token = new byte[20];
        new SecureRandom().nextBytes(token);
        byte[] signature = keyPair.signAuthToken(token);
        assertEquals(256, signature.length);

        Cipher verifyCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        verifyCipher.init(Cipher.DECRYPT_MODE, reconstructedPub);
        byte[] recoveredDigestInfo = verifyCipher.doFinal(signature);
        assertEquals(35, recoveredDigestInfo.length);
        assertArrayEquals(token, Arrays.copyOfRange(recoveredDigestInfo, 15, 35));
    }

    @Test
    public void adbSpake2_aliceAndBobAgreeOnSameKeyAndEncryptDecryptPeerInfo() throws Exception {
        byte[] labelBytes = AdbPairingClient.EXPORTED_KEY_LABEL.getBytes(StandardCharsets.US_ASCII);
        assertEquals(10, labelBytes.length);
        assertEquals(0, labelBytes[9]);
        assertEquals("adb-label\u0000", AdbPairingClient.EXPORTED_KEY_LABEL);

        byte[] password = "482915".getBytes(StandardCharsets.UTF_8);
        AdbSpake2 alice = new AdbSpake2(password);
        byte[] aliceMsg = alice.generateMessage();
        assertEquals(32, aliceMsg.length);

        // Simulated ADB daemon (Bob) SPAKE2 side
        Spake2ServerSim bob = new Spake2ServerSim(password);
        byte[] bobMsg = bob.generateMessage();
        assertEquals(32, bobMsg.length);

        alice.processPeerMessage(bobMsg);
        bob.processClientMessage(aliceMsg);

        byte[] peerInfoPlain = new byte[8192];
        peerInfoPlain[0] = 0x00; // ADB_RSA_PUB_KEY
        byte[] pub = "dummy-adb-pubkey\0".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(pub, 0, peerInfoPlain, 1, pub.length);

        byte[] encryptedFromAlice = alice.encrypt(peerInfoPlain);
        byte[] decryptedByBob = bob.decrypt(encryptedFromAlice);
        assertArrayEquals(peerInfoPlain, decryptedByBob);

        byte[] bobReply = new byte[8192];
        bobReply[0] = 0x01; // ADB_DEVICE_GUID
        byte[] encryptedFromBob = bob.encrypt(bobReply);
        byte[] decryptedByAlice = alice.decrypt(encryptedFromBob);
        assertArrayEquals(bobReply, decryptedByAlice);
    }

    @Test
    public void adbSpake2_clearsCofactorEightSmallSubgroupPoint() throws Exception {
        byte[] password = "482915".getBytes(StandardCharsets.UTF_8);
        AdbSpake2 alice = new AdbSpake2(password);
        byte[] aliceMsg = alice.generateMessage();

        Spake2ServerSim bob = new Spake2ServerSim(password);
        byte[] bobMsg = bob.generateMessage();

        // Perturb bobMsg by adding order-2 torsion point (0, p - 1) and override bobMsg hash input:
        // proves privateScalar * ((Y + T) - wN) == privateScalar * (Y - wN) because privateScalar is divisible by 8.
        BigInteger[] bobPt = Spake2ServerSim.decode(bobMsg);
        BigInteger[] order2 = new BigInteger[]{BigInteger.ZERO, Spake2ServerSim.P.subtract(BigInteger.ONE)};
        byte[] perturbedBobMsg = Spake2ServerSim.encode(Spake2ServerSim.add(bobPt, order2));

        bob.overrideBobMsgForTranscript(perturbedBobMsg);
        alice.processPeerMessage(perturbedBobMsg);
        bob.processClientMessage(aliceMsg);

        byte[] pt = "cofactor-check".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(pt, bob.decrypt(alice.encrypt(pt)));
        assertArrayEquals(pt, alice.decrypt(bob.encrypt(pt)));
    }

    @Test
    public void chatAiServerManager_verifiesLiveLoopbackPingAndExecAndIntegratesWithShizukuManager() throws Exception {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        ChatAiServerManager serverMgr = app.getAppContainer().getChatAiServerManager();
        ShizukuIntegrationManager shizukuMgr = app.getAppContainer().getShizukuIntegrationManager();

        String token = "test-secret-token-123";
        AtomicBoolean serverRunning = new AtomicBoolean(true);
        ServerSocket ss = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        int port = ss.getLocalPort();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            while (serverRunning.get() && !ss.isClosed()) {
                try (Socket client = ss.accept()) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter w = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
                    String line = r.readLine();
                    if (line == null) continue;
                    if (line.equals(token + " PING")) {
                        w.println("PONG 2000 4321");
                    } else if (line.startsWith(token + " EXEC ")) {
                        JsonObject resp = new JsonObject();
                        resp.addProperty("exitCode", 0);
                        resp.addProperty("uid", 2000);
                        resp.addProperty("timedOut", false);
                        resp.addProperty("stdout", Base64.encodeToString("uid=2000(shell)\n".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                        resp.addProperty("stderr", "");
                        w.println(resp.toString());
                    }
                } catch (Exception ignored) {
                }
            }
        });

        try {
            serverMgr.configureEndpointForTesting(port, token);
            ChatAiServerManager.ServerState live = serverMgr.refreshServerStatusSync();
            assertTrue(live.isRunning());
            assertEquals(ChatAiServerManager.ServerStatus.RUNNING, live.status);
            assertEquals(2000, live.uid);
            assertEquals(4321, live.pid);

            ShizukuIntegrationManager.ShizukuState shizukuState = shizukuMgr.inspectState();
            assertEquals(IntegrationStatus.ACTIVE, shizukuState.status);
            assertEquals(ShizukuIntegrationManager.PrivilegeLevel.SHELL, shizukuState.privilegeLevel);
            assertEquals(2000, shizukuState.serviceUid);

            ShizukuIntegrationManager.ShellResult shellResult = shizukuMgr.executeShell("id", null, 5);
            assertTrue(shellResult.isSuccess());
            assertEquals(2000, shellResult.uid);
            assertTrue(shellResult.stdout.contains("uid=2000(shell)"));
        } finally {
            serverRunning.set(false);
            ss.close();
            exec.shutdownNow();
        }

        // Honest verification: once socket closes, status flips back to STOPPED
        ChatAiServerManager.ServerState stopped = serverMgr.refreshServerStatusSync();
        assertFalse(stopped.isRunning());
        assertEquals(ChatAiServerManager.ServerStatus.STOPPED, stopped.status);
    }

    @Test
    public void settingsActivity_rendersPairingViaWirelessDebuggingGroupAndHandlesActions() throws Exception {
        ChatAiApplication app = ApplicationProvider.getApplicationContext();
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0);

        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        TextView groupTitle = activity.findViewById(R.id.tv_group_wireless_adb);
        TextView statusTitle = activity.findViewById(R.id.tv_wireless_adb_status_title);
        Button btnPairing = activity.findViewById(R.id.btn_wireless_adb_pairing);
        Button btnStart = activity.findViewById(R.id.btn_wireless_adb_start);

        assertNotNull(groupTitle);
        assertEquals("Pairing via Wireless Debugging", groupTitle.getText().toString());
        assertNotNull(statusTitle);
        assertEquals(app.getString(R.string.wireless_adb_status_stopped), statusTitle.getText().toString());
        assertNotNull(btnPairing);
        assertEquals("Pairing", btnPairing.getText().toString());
        assertNotNull(btnStart);
        assertEquals("Start", btnStart.getText().toString());

        // Tapping Pairing when Developer Options is OFF should display the guide dialog
        btnPairing.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Dialog latestDialog = ShadowAlertDialog.getLatestDialog();
        assertNotNull(latestDialog);
        assertTrue(latestDialog.isShowing());
        latestDialog.dismiss();

        // Tapping Start launches the dark/gray console WirelessAdbStarterActivity
        btnStart.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent started = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(WirelessAdbStarterActivity.class.getName(), started.getComponent().getClassName());

        controller.pause().stop().destroy();
    }

    /**
     * Minimal Ed25519 SPAKE2 Bob (server) test peer implementing AOSP/BoringSSL equations
     * to verify full cryptographic compatibility with AdbSpake2 (Alice).
     */
    private static final class Spake2ServerSim {
        private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
        private static final BigInteger L = BigInteger.ONE.shiftLeft(252)
                .add(new BigInteger("27742317777372353535851937790883648493"));
        private static final BigInteger D = BigInteger.valueOf(-121665)
                .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
        private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
                .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

        private static final BigInteger[] B = decode("5866666666666666666666666666666666666666666666666666666666666666");
        private static final BigInteger[] M = decode("d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf");
        private static final BigInteger[] N = decode("d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab");

        private final byte[] pwHash;
        private final BigInteger w;
        private final BigInteger yScalar;
        private byte[] bobMsg;
        private SecretKeySpec aesKey;
        private long encSeq = 0;
        private long decSeq = 0;

        Spake2ServerSim(byte[] pw) throws Exception {
            this.pwHash = MessageDigest.getInstance("SHA-512").digest(pw);
            this.w = fromLe(pwHash).mod(L);
            SecureRandom sr = new SecureRandom();
            BigInteger raw;
            do {
                byte[] priv = new byte[32];
                sr.nextBytes(priv);
                priv[31] &= 0x0F;
                raw = fromLe(priv);
            } while (raw.equals(BigInteger.ZERO) || raw.compareTo(L) >= 0);
            this.yScalar = raw.shiftLeft(3);
        }

        byte[] generateMessage() {
            BigInteger[] yB = mult(B, yScalar);
            BigInteger[] wN = mult(N, w);
            this.bobMsg = encode(add(yB, wN));
            return bobMsg;
        }

        void overrideBobMsgForTranscript(byte[] customBobMsg) {
            this.bobMsg = customBobMsg;
        }

        void processClientMessage(byte[] aliceMsg) throws Exception {
            BigInteger[] xPt = decode(aliceMsg);
            BigInteger[] wM = mult(M, w);
            BigInteger[] kPt = mult(add(xPt, new BigInteger[]{P.subtract(wM[0]).mod(P), wM[1]}), yScalar);
            byte[] kBytes = encode(kPt);

            MessageDigest md = MessageDigest.getInstance("SHA-512");
            upd(md, "adb pair client\0".getBytes(StandardCharsets.US_ASCII));
            upd(md, "adb pair server\0".getBytes(StandardCharsets.US_ASCII));
            upd(md, aliceMsg);
            upd(md, bobMsg);
            upd(md, kBytes);
            upd(md, pwHash);

            byte[] shared = md.digest();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update("adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) 1);
            byte[] keyBytes = Arrays.copyOf(mac.doFinal(), 16);
            aesKey = new SecretKeySpec(keyBytes, "AES");
        }

        byte[] encrypt(byte[] pt) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce(encSeq++)));
            return c.doFinal(pt);
        }

        byte[] decrypt(byte[] ct) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(128, nonce(decSeq++)));
            return c.doFinal(ct);
        }

        private static byte[] nonce(long s) {
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(s).putInt(0).array();
        }

        private static void upd(MessageDigest md, byte[] b) {
            md.update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(b.length).array());
            md.update(b);
        }

        private static BigInteger[] add(BigInteger[] p, BigInteger[] q) {
            BigInteger x1x2 = p[0].multiply(q[0]).mod(P);
            BigInteger y1y2 = p[1].multiply(q[1]).mod(P);
            BigInteger dx = D.multiply(x1x2).multiply(y1y2).mod(P);
            BigInteger rx = p[0].multiply(q[1]).add(p[1].multiply(q[0])).mod(P)
                    .multiply(BigInteger.ONE.add(dx).modInverse(P)).mod(P);
            BigInteger ry = y1y2.add(x1x2).mod(P)
                    .multiply(BigInteger.ONE.subtract(dx).modInverse(P)).mod(P);
            return new BigInteger[]{rx, ry};
        }

        private static BigInteger[] mult(BigInteger[] pt, BigInteger sc) {
            BigInteger[] acc = new BigInteger[]{BigInteger.ZERO, BigInteger.ONE};
            BigInteger[] cur = pt;
            for (int i = 0; i < sc.bitLength(); i++) {
                if (sc.testBit(i)) acc = add(acc, cur);
                cur = add(cur, cur);
            }
            return acc;
        }

        private static byte[] encode(BigInteger[] pt) {
            byte[] be = pt[1].toByteArray();
            byte[] out = new byte[32];
            int start = be[0] == 0 ? 1 : 0;
            int len = Math.min(32, be.length - start);
            for (int i = 0; i < len; i++) out[i] = be[be.length - 1 - i];
            if (pt[0].testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }

        private static BigInteger[] decode(String hex) {
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < hex.length(); i += 2) {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
            return decode(out);
        }

        private static BigInteger[] decode(byte[] enc) {
            byte[] copy = Arrays.copyOf(enc, 32);
            int xSign = (copy[31] & 0x80) != 0 ? 1 : 0;
            copy[31] &= 0x7F;
            BigInteger y = fromLe(copy);
            BigInteger y2 = y.multiply(y).mod(P);
            BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
            BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
            BigInteger v3 = v.pow(3).mod(P);
            BigInteger v7 = v3.multiply(v3).multiply(v).mod(P);
            BigInteger x = u.multiply(v3).multiply(u.multiply(v7).mod(P)
                    .modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
            if (!v.multiply(x.multiply(x).mod(P)).mod(P).equals(u)) {
                x = x.multiply(SQRT_M1).mod(P);
            }
            if ((x.testBit(0) ? 1 : 0) != xSign) x = P.subtract(x).mod(P);
            return new BigInteger[]{x, y};
        }

        private static BigInteger fromLe(byte[] le) {
            byte[] be = new byte[le.length + 1];
            for (int i = 0; i < le.length; i++) be[le.length - i] = le[i];
            return new BigInteger(1, be);
        }
    }
}
