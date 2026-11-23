package com.chatai.an.ai.integration.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.R;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.core.AppLogger;
import com.chatai.an.presentation.settings.SettingsActivity;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Self-contained Wireless ADB Pairing & ChatAI Server Manager (Android 11+).
 * Operates completely independently of any external Shizuku app and serves ChatAI internal UID 2000 access only.
 */
public final class ChatAiServerManager {

    private static final String TAG = "ChatAiServerManager";
    private static final String PREFS_NAME = "chatai_wireless_adb_prefs";
    private static final String KEY_PAIRED = "adb_paired";
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_SERVER_TOKEN = "server_token";
    private static final String KEY_PAIRING_HOST = "pairing_host";
    private static final String KEY_PAIRING_PORT = "pairing_port";
    private static final String KEY_CONNECT_HOST = "connect_host";
    private static final String KEY_CONNECT_PORT = "connect_port";

    public static final String PAIRING_CHANNEL_ID = "chatai_wireless_adb_pairing";
    public static final int PAIRING_NOTIF_ID = 9531;
    public static final String REMOTE_INPUT_KEY = "key_adb_pairing_code";

    private static final int DEFAULT_SERVER_PORT = 39527;

    public enum ServerStatus {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR
    }

    public static final class ServerState {
        public final ServerStatus status;
        public final boolean paired;
        public final boolean developerOptionsEnabled;
        public final int uid;
        public final int pid;
        public final int serverPort;
        public final String detail;

        public ServerState(
                ServerStatus status,
                boolean paired,
                boolean developerOptionsEnabled,
                int uid,
                int pid,
                int serverPort,
                String detail
        ) {
            this.status = status;
            this.paired = paired;
            this.developerOptionsEnabled = developerOptionsEnabled;
            this.uid = uid;
            this.pid = pid;
            this.serverPort = serverPort;
            this.detail = detail != null ? detail : "";
        }

        public boolean isRunning() {
            return status == ServerStatus.RUNNING;
        }
    }

    public static final class ShellResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        public final int uid;

        public ShellResult(int exitCode, String stdout, String stderr, boolean timedOut, int uid) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.timedOut = timedOut;
            this.uid = uid;
        }
    }

    public interface ConsoleLogger {
        void onLog(String line, boolean isError);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final AdbKeyPair keyPair;
    private final AdbPairingClient pairingClient;
    private final MutableLiveData<ServerState> stateLiveData = new MutableLiveData<>();
    private final MutableLiveData<String> pairingEventLiveData = new MutableLiveData<>("");
    private final Gson gson = new Gson();

    private volatile String activePairingServiceName = "";
    private volatile String activePairingHost = "";
    private volatile int activePairingPort = -1;
    private volatile ServerState latestState;

    public ChatAiServerManager(@NonNull Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.keyPair = new AdbKeyPair(this.context);
        this.pairingClient = new AdbPairingClient(this.keyPair);
        this.activePairingHost = prefs.getString(KEY_PAIRING_HOST, "");
        this.activePairingPort = prefs.getInt(KEY_PAIRING_PORT, -1);

        createPairingNotificationChannel();
        ServerState initial = new ServerState(
                ServerStatus.STOPPED,
                isPaired(),
                isDeveloperOptionsEnabled(),
                -1,
                -1,
                prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT),
                this.context.getString(R.string.wireless_adb_status_stopped)
        );
        this.latestState = initial;
        stateLiveData.postValue(initial);
    }

    public LiveData<ServerState> observeState() {
        return stateLiveData;
    }

    public ServerState getCurrentStateSnapshot() {
        return latestState;
    }

    public LiveData<String> observePairingEvents() {
        return pairingEventLiveData;
    }

    public boolean isDeveloperOptionsEnabled() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isWirelessDebuggingSettingEnabled() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isPaired() {
        return prefs.getBoolean(KEY_PAIRED, false) && keyPair.hasKeyPair();
    }

    public AdbKeyPair getKeyPair() {
        return keyPair;
    }

    public void refreshServerStatusAsync() {
        AppExecutors.getInstance().worker().execute(this::refreshServerStatusSync);
    }

    /**
     * Performs a real TCP loopback PING against the live ChatAiServerMain process (never trusts stale flags).
     */
    public ServerState refreshServerStatusSync() {
        boolean devOpts = isDeveloperOptionsEnabled();
        boolean paired = isPaired();
        int port = prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
        String token = prefs.getString(KEY_SERVER_TOKEN, "");

        if (port > 0 && !token.isEmpty()) {
            PongInfo pong = pingServer(port, token);
            if (pong != null && pong.alive) {
                ServerState running = new ServerState(
                        ServerStatus.RUNNING,
                        paired,
                        devOpts,
                        pong.uid,
                        pong.pid,
                        port,
                        context.getString(R.string.wireless_adb_status_running)
                );
                this.publishState(running);
                return running;
            }
        }

        ServerState stopped = new ServerState(
                ServerStatus.STOPPED,
                paired,
                devOpts,
                -1,
                -1,
                port,
                context.getString(R.string.wireless_adb_status_stopped)
        );
        this.publishState(stopped);
        return stopped;
    }

    private void publishState(ServerState state) {
        this.latestState = state;
        stateLiveData.postValue(state);
    }

    @VisibleForTesting
    public void configureEndpointForTesting(int port, String token) {
        prefs.edit()
                .putInt(KEY_SERVER_PORT, port)
                .putString(KEY_SERVER_TOKEN, token)
                .apply();
    }

    public boolean isServerAliveSync() {
        return refreshServerStatusSync().isRunning();
    }

    public void startPairingDiscoveryService() {
        try {
            Intent intent = new Intent(context, EmbeddedAdbForegroundService.class);
            intent.setAction(EmbeddedAdbForegroundService.ACTION_START_PAIRING_DISCOVERY);
            ContextCompat.startForegroundService(context, intent);
        } catch (Throwable t) {
            AppLogger.e(TAG, "Failed to start pairing discovery service", t);
        }
    }

    public void stopPairingDiscoveryService() {
        try {
            Intent intent = new Intent(context, EmbeddedAdbForegroundService.class);
            intent.setAction(EmbeddedAdbForegroundService.ACTION_STOP_PAIRING_DISCOVERY);
            context.startService(intent);
        } catch (Throwable ignored) {
        }
    }

    public void onPairingServiceDiscovered(@NonNull String host, int port) {
        onPairingServiceDiscovered("", host, port);
    }

    public void onPairingServiceDiscovered(String serviceName, @NonNull String host, int port) {
        this.activePairingServiceName = serviceName != null ? serviceName : "";
        this.activePairingHost = host;
        this.activePairingPort = port;
        prefs.edit()
                .putString(KEY_PAIRING_HOST, host)
                .putInt(KEY_PAIRING_PORT, port)
                .apply();
        pairingEventLiveData.postValue("Pairing service found: " + host + ":" + port);
        postPairingServiceFoundNotification(host, port, null);
    }

    public void onPairingServiceLost(String serviceName) {
        if (serviceName != null && serviceName.equals(activePairingServiceName)) {
            this.activePairingServiceName = "";
            this.activePairingHost = "";
            this.activePairingPort = -1;
            prefs.edit()
                    .remove(KEY_PAIRING_HOST)
                    .remove(KEY_PAIRING_PORT)
                    .apply();
            postSearchingPairingNotification();
        }
    }

    public void postSearchingPairingNotification() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(PAIRING_NOTIF_ID, buildSearchingNotification());
    }

    public Notification buildSearchingNotification() {
        Intent settingsIntent = new Intent(context, SettingsActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(context, 401, settingsIntent, piFlags(false));

        Intent stopIntent = new Intent(context, AdbPairingNotificationReceiver.class);
        stopIntent.setAction(AdbPairingNotificationReceiver.ACTION_STOP_PAIRING);
        PendingIntent stopPi = PendingIntent.getBroadcast(context, 402, stopIntent, piFlags(false));

        return new NotificationCompat.Builder(context, PAIRING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(context.getString(R.string.adb_notif_searching_title))
                .setContentText(context.getString(R.string.adb_notif_searching_body))
                .setContentIntent(contentPi)
                .addAction(R.drawable.ic_close, context.getString(R.string.action_cancel), stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
    }

    public void postPairingServiceFoundNotification(@NonNull String host, int port, String errorText) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        RemoteInput remoteInput = new RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel(context.getString(R.string.adb_notif_action_enter_code))
                .build();

        Intent replyIntent = new Intent(context, AdbPairingNotificationReceiver.class);
        replyIntent.setAction(AdbPairingNotificationReceiver.ACTION_SUBMIT_PAIRING_CODE);
        replyIntent.putExtra(AdbPairingNotificationReceiver.EXTRA_HOST, host);
        replyIntent.putExtra(AdbPairingNotificationReceiver.EXTRA_PORT, port);
        PendingIntent replyPi = PendingIntent.getBroadcast(context, 403, replyIntent, piFlags(true));

        NotificationCompat.Action enterCodeAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_key,
                context.getString(R.string.adb_notif_action_enter_code),
                replyPi
        )
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build();

        Intent settingsIntent = new Intent(context, SettingsActivity.class);
        PendingIntent contentPi = PendingIntent.getActivity(context, 404, settingsIntent, piFlags(false));

        String contentText = (errorText != null && !errorText.isEmpty())
                ? errorText
                : (host + ":" + port);

        Notification notif = new NotificationCompat.Builder(context, PAIRING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(context.getString(R.string.adb_notif_found_title))
                .setContentText(contentText)
                .setContentIntent(contentPi)
                .addAction(enterCodeAction)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build();

        nm.notify(PAIRING_NOTIF_ID, notif);
    }

    public void postPairingResultNotification(boolean success, @NonNull String detail) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Intent settingsIntent = new Intent(context, SettingsActivity.class);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(context, 405, settingsIntent, piFlags(false));

        Notification notif = new NotificationCompat.Builder(context, PAIRING_CHANNEL_ID)
                .setSmallIcon(success ? R.drawable.ic_check : R.drawable.ic_adb)
                .setContentTitle(success
                        ? context.getString(R.string.adb_notif_pairing_success_title)
                        : context.getString(R.string.adb_notif_pairing_failed_title))
                .setContentText(detail)
                .setContentIntent(contentPi)
                .setAutoCancel(true)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        nm.notify(PAIRING_NOTIF_ID, notif);
    }

    public void dismissPairingNotification() {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.cancel(PAIRING_NOTIF_ID);
        }
    }

    public AdbPairingClient.PairingOutcome submitPairingCodeSync(String hostArg, int portArg, @NonNull String pin) {
        String host = (activePairingHost != null && !activePairingHost.isEmpty() && activePairingPort > 0)
                ? activePairingHost
                : hostArg;
        int port = (activePairingHost != null && !activePairingHost.isEmpty() && activePairingPort > 0)
                ? activePairingPort
                : portArg;

        if (host == null || host.isEmpty() || port <= 0) {
            AdbMdnsDiscovery discovery = new AdbMdnsDiscovery(context);
            AdbMdnsDiscovery.ServiceEndpoint ep = discovery.discoverOnceSync(AdbMdnsDiscovery.SERVICE_PAIRING, 3000);
            if (ep != null) {
                host = ep.host;
                port = ep.port;
                this.activePairingServiceName = ep.serviceName;
                this.activePairingHost = ep.host;
                this.activePairingPort = ep.port;
            }
        }

        if (host == null || host.isEmpty() || port <= 0) {
            String msg = context.getString(R.string.adb_err_pairing_service_missing);
            postPairingResultNotification(false, msg);
            pairingEventLiveData.postValue(msg);
            return new AdbPairingClient.PairingOutcome(false, msg);
        }

        AdbPairingClient.PairingOutcome outcome = pairingClient.pair(host, port, pin);
        if (outcome.success) {
            prefs.edit().putBoolean(KEY_PAIRED, true).apply();
            stopPairingDiscoveryService();
            postPairingResultNotification(true, context.getString(R.string.adb_notif_pairing_success_body));
            pairingEventLiveData.postValue("Pairing successful");
            refreshServerStatusSync();
        } else {
            postPairingServiceFoundNotification(host, port, outcome.message);
            pairingEventLiveData.postValue(outcome.message);
        }
        return outcome;
    }

    /**
     * Executes the real Wireless ADB starter flow and launches ChatAiServerMain (UID 2000).
     * Emits console logs ending with "Info: starter exit with code 0" and "Info: chatai_server started" on success.
     */
    public boolean startServerWithConsole(@NonNull ConsoleLogger logger) {
        publishState(new ServerState(
                ServerStatus.STARTING,
                isPaired(),
                isDeveloperOptionsEnabled(),
                -1,
                -1,
                DEFAULT_SERVER_PORT,
                context.getString(R.string.wireless_adb_status_starting)
        ));

        logger.onLog("Info: initializing ChatAI Wireless ADB starter...", false);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            logger.onLog("Error: Wireless Debugging requires Android 11 (API 30) or higher.", true);
            refreshServerStatusSync();
            return false;
        }

        if (!isDeveloperOptionsEnabled()) {
            logger.onLog("Error: Developer Options are disabled. Please enable Developer Options first.", true);
            refreshServerStatusSync();
            return false;
        }

        // Check if already alive first
        int existingPort = prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
        String existingToken = prefs.getString(KEY_SERVER_TOKEN, "");
        if (!existingToken.isEmpty()) {
            PongInfo existing = pingServer(existingPort, existingToken);
            if (existing != null && existing.alive) {
                logger.onLog("Info: existing chatai_server detected (UID " + existing.uid + ", PID " + existing.pid + ")", false);
                logger.onLog("Info: starter exit with code 0", false);
                logger.onLog("Info: chatai_server started", false);
                refreshServerStatusSync();
                return true;
            }
        }

        if (!keyPair.hasKeyPair()) {
            logger.onLog("Error: No ADB pairing keys found. Please complete Pairing first.", true);
            refreshServerStatusSync();
            return false;
        }

        logger.onLog("Info: scanning for Wireless Debugging (_adb-tls-connect._tcp)...", false);
        AdbMdnsDiscovery discovery = new AdbMdnsDiscovery(context);
        AdbMdnsDiscovery.ServiceEndpoint connectEp = discovery.discoverOnceSync(AdbMdnsDiscovery.SERVICE_CONNECT, 5500);

        String connectHost;
        int connectPort;
        if (connectEp != null) {
            connectHost = connectEp.host;
            connectPort = connectEp.port;
            prefs.edit()
                    .putString(KEY_CONNECT_HOST, connectHost)
                    .putInt(KEY_CONNECT_PORT, connectPort)
                    .apply();
            logger.onLog("Info: found Wireless Debugging at " + connectHost + ":" + connectPort, false);
        } else {
            String cachedHost = prefs.getString(KEY_CONNECT_HOST, "");
            int cachedPort = prefs.getInt(KEY_CONNECT_PORT, -1);
            if (!cachedHost.isEmpty() && cachedPort > 0 && isWirelessDebuggingSettingEnabled()) {
                connectHost = cachedHost;
                connectPort = cachedPort;
                logger.onLog("Info: mDNS timeout, trying cached endpoint " + connectHost + ":" + connectPort, false);
            } else {
                logger.onLog("Error: Wireless Debugging service not found.", true);
                logger.onLog("Hint: Enable Settings -> Developer options -> Wireless debugging, then retry.", true);
                refreshServerStatusSync();
                return false;
            }
        }

        String launchToken = generateToken();
        int targetPort = DEFAULT_SERVER_PORT;

        try (AdbProtocolClient adb = new AdbProtocolClient(keyPair)) {
            logger.onLog("Info: connecting TLS ADB session to " + connectHost + ":" + connectPort + "...", false);
            adb.connect(connectHost, connectPort, 8_000);
            logger.onLog("Info: ADB TLS authentication verified.", false);

            // Verify shell UID 2000 and resolve APK path
            AdbProtocolClient.ShellOutput idOut = adb.executeShell("id -u && pm path " + context.getPackageName(), 8_000);
            logger.onLog("Info: adb shell uid check: " + idOut.stdout.replace("\n", " | ").trim(), false);

            String apkPath = extractApkPath(idOut.stdout);
            if (apkPath.isEmpty()) {
                logger.onLog("Error: unable to resolve ChatAI APK path via pm path.", true);
                refreshServerStatusSync();
                return false;
            }

            // Stop any lingering old server process before launching fresh instance
            adb.executeShell("pkill -f com.chatai.an.ai.integration.adb.ChatAiServerMain >/dev/null 2>&1 || true", 5_000);

            String starterCmd = String.format(
                    Locale.US,
                    "CLASSPATH='%s' nohup app_process /system/bin %s --port=%d --token=%s >/dev/null 2>&1 </dev/null & echo STARTER_OK",
                    apkPath.replace("'", "'\"'\"'"),
                    ChatAiServerMain.class.getName(),
                    targetPort,
                    launchToken
            );

            logger.onLog("Info: launching chatai_server via app_process (UID 2000)...", false);
            AdbProtocolClient.ShellOutput startOut = adb.executeShell(starterCmd, 8_000);
            if (startOut.exitCode != 0 && !startOut.stdout.contains("STARTER_OK")) {
                logger.onLog("Error: starter failed with exit code " + startOut.exitCode + " " + startOut.stderr, true);
                refreshServerStatusSync();
                return false;
            }

            prefs.edit()
                    .putBoolean(KEY_PAIRED, true)
                    .putInt(KEY_SERVER_PORT, targetPort)
                    .putString(KEY_SERVER_TOKEN, launchToken)
                    .apply();

            // Wait and verify live PING/PONG from the launched UID 2000 server
            PongInfo pong = null;
            for (int attempt = 0; attempt < 15; attempt++) {
                Thread.sleep(250);
                pong = pingServer(targetPort, launchToken);
                if (pong != null && pong.alive) {
                    break;
                }
            }

            if (pong == null || !pong.alive) {
                logger.onLog("Error: chatai_server did not respond to liveness ping on 127.0.0.1:" + targetPort, true);
                refreshServerStatusSync();
                return false;
            }

            logger.onLog("Info: chatai_server verified alive (UID " + pong.uid + ", PID " + pong.pid + ")", false);
            logger.onLog("Info: starter exit with code 0", false);
            logger.onLog("Info: chatai_server started", false);
            refreshServerStatusSync();
            return true;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.onLog("Error: ADB starter failed: " + errMsg, true);
            if (errMsg.toLowerCase(Locale.ROOT).contains("handshake") || errMsg.toLowerCase(Locale.ROOT).contains("unexpected adb")) {
                logger.onLog("Hint: Pairing key not accepted by device. Run Pairing again.", true);
            }
            refreshServerStatusSync();
            return false;
        }
    }

    public ShellResult executeShell(@NonNull String command, String workdir, int timeoutSec) throws IOException {
        int port = prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
        String token = prefs.getString(KEY_SERVER_TOKEN, "");
        if (token.isEmpty()) {
            throw new IllegalStateException("ChatAI Server chưa khởi chạy.");
        }

        String b64Dir = (workdir != null && !workdir.trim().isEmpty())
                ? Base64.encodeToString(workdir.trim().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)
                : "-";
        String b64Cmd = Base64.encodeToString(command.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        int clampedTimeout = Math.max(3, Math.min(120, timeoutSec));

        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            sock.setSoTimeout((clampedTimeout + 5) * 1000);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));

            writer.println(token + " EXEC " + clampedTimeout + " " + b64Dir + " " + b64Cmd);
            String resp = reader.readLine();
            if (resp == null || resp.startsWith("ERR")) {
                refreshServerStatusSync();
                throw new IOException("ChatAI Server rejected or closed request: " + resp);
            }
            JsonObject obj = gson.fromJson(resp, JsonObject.class);
            int exitCode = obj.has("exitCode") ? obj.get("exitCode").getAsInt() : -1;
            int uid = obj.has("uid") ? obj.get("uid").getAsInt() : 2000;
            boolean timedOut = obj.has("timedOut") && obj.get("timedOut").getAsBoolean();
            String stdout = decodeB64(obj.has("stdout") ? obj.get("stdout").getAsString() : "");
            String stderr = decodeB64(obj.has("stderr") ? obj.get("stderr").getAsString() : "");
            return new ShellResult(exitCode, stdout, stderr, timedOut, uid);
        } catch (IOException e) {
            refreshServerStatusSync();
            throw e;
        }
    }

    public void stopServerAsync() {
        AppExecutors.getInstance().worker().execute(() -> {
            int port = prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
            String token = prefs.getString(KEY_SERVER_TOKEN, "");
            if (!token.isEmpty()) {
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
                    sock.setSoTimeout(1_500);
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
                    writer.println(token + " STOP");
                } catch (Exception ignored) {
                }
            }
            refreshServerStatusSync();
        });
    }

    private PongInfo pingServer(int port, String token) {
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", port), 600);
            sock.setSoTimeout(800);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            writer.println(token + " PING");
            String line = reader.readLine();
            if (line != null && line.startsWith("PONG ")) {
                String[] parts = line.split(" ");
                int uid = parts.length >= 2 ? Integer.parseInt(parts[1]) : 2000;
                int pid = parts.length >= 3 ? Integer.parseInt(parts[2]) : -1;
                return new PongInfo(true, uid, pid);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void createPairingNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(PAIRING_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        PAIRING_CHANNEL_ID,
                        context.getString(R.string.adb_pairing_channel_name),
                        NotificationManager.IMPORTANCE_HIGH
                );
                ch.setDescription(context.getString(R.string.adb_pairing_channel_desc));
                nm.createNotificationChannel(ch);
            }
        }
    }

    private static String extractApkPath(String stdout) {
        if (stdout == null) return "";
        for (String line : stdout.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package:")) {
                return trimmed.substring("package:".length()).trim();
            }
        }
        return "";
    }

    private static String decodeB64(String b64) {
        if (b64 == null || b64.isEmpty()) return "";
        try {
            return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static int piFlags(boolean mutableForRemoteInput) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (mutableForRemoteInput) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
        }
        return flags;
    }

    private static final class PongInfo {
        final boolean alive;
        final int uid;
        final int pid;

        PongInfo(boolean alive, int uid, int pid) {
            this.alive = alive;
            this.uid = uid;
            this.pid = pid;
        }
    }
}
