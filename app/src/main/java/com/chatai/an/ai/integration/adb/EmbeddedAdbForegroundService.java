package com.chatai.an.ai.integration.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.chatai.an.R;
import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.core.AppLogger;
import com.chatai.an.presentation.settings.SettingsActivity;

/**
 * Foreground Service for Wireless ADB mDNS pairing discovery and server keepalive.
 * Ensures Android does not throttle NsdManager discovery while the user navigates to
 * System Developer Options -> Wireless Debugging -> Pair device with pairing code.
 */
public class EmbeddedAdbForegroundService extends Service {
    private static final String TAG = "EmbeddedAdbFgs";
    public static final String CHANNEL_ID = "embedded_adb_channel";
    public static final int NOTIF_ID = 9527;

    public static final String ACTION_START = "com.chatai.an.action.START_EMBEDDED_ADB";
    public static final String ACTION_STOP = "com.chatai.an.action.STOP_EMBEDDED_ADB";
    public static final String ACTION_START_PAIRING_DISCOVERY = "com.chatai.an.action.START_PAIRING_DISCOVERY";
    public static final String ACTION_STOP_PAIRING_DISCOVERY = "com.chatai.an.action.STOP_PAIRING_DISCOVERY";

    private static final long PAIRING_TIMEOUT_MS = 5 * 60 * 1000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AdbMdnsDiscovery mdnsDiscovery;
    private Runnable timeoutRunnable;
    private volatile boolean pairingModeActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopPairingDiscoveryInternal(true);
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP_PAIRING_DISCOVERY.equals(action)) {
            stopPairingDiscoveryInternal(false);
            // Detach rather than removing notification so "Pairing successful" remains visible
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_PAIRING_DISCOVERY.equals(action)) {
            startPairingDiscoveryInternal();
            return START_NOT_STICKY;
        }

        // Legacy ACTION_START fallback: keep brief status notification and refresh server liveness
        startForeground(NOTIF_ID, buildLegacyNotification(getString(R.string.wireless_adb_title)));
        ChatAiServerManager mgr = getServerManager();
        if (mgr != null) {
            mgr.refreshServerStatusAsync();
        }
        return START_NOT_STICKY;
    }

    private void startPairingDiscoveryInternal() {
        ChatAiServerManager mgr = getServerManager();
        if (mgr == null) {
            stopSelf();
            return;
        }

        startForeground(ChatAiServerManager.PAIRING_NOTIF_ID, mgr.buildSearchingNotification());
        pairingModeActive = true;

        if (mdnsDiscovery != null) {
            mdnsDiscovery.stopDiscovery();
        }
        mdnsDiscovery = new AdbMdnsDiscovery(this);
        mdnsDiscovery.startDiscovery(AdbMdnsDiscovery.SERVICE_PAIRING, new AdbMdnsDiscovery.DiscoveryCallback() {
            @Override
            public void onServiceFound(AdbMdnsDiscovery.ServiceEndpoint endpoint) {
                if (!pairingModeActive) return;
                AppLogger.i(TAG, "Pairing service discovered: " + endpoint);
                mgr.onPairingServiceDiscovered(endpoint.serviceName, endpoint.host, endpoint.port);
            }

            @Override
            public void onServiceLost(String serviceName) {
                AppLogger.d(TAG, "Pairing service lost: " + serviceName);
                if (pairingModeActive) {
                    mgr.onPairingServiceLost(serviceName);
                }
            }

            @Override
            public void onDiscoveryFailed(String error) {
                AppLogger.w(TAG, "Pairing mDNS discovery failed: " + error);
                if (pairingModeActive) {
                    mgr.postPairingResultNotification(false, error);
                }
            }
        });

        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
        timeoutRunnable = () -> {
            if (pairingModeActive) {
                stopPairingDiscoveryInternal(true);
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        };
        mainHandler.postDelayed(timeoutRunnable, PAIRING_TIMEOUT_MS);
    }

    private void stopPairingDiscoveryInternal(boolean dismissNotification) {
        pairingModeActive = false;
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (mdnsDiscovery != null) {
            mdnsDiscovery.stopDiscovery();
            mdnsDiscovery = null;
        }
        if (dismissNotification) {
            ChatAiServerManager mgr = getServerManager();
            if (mgr != null) {
                mgr.dismissPairingNotification();
            }
        }
    }

    @Nullable
    private ChatAiServerManager getServerManager() {
        if (getApplicationContext() instanceof ChatAiApplication) {
            return ((ChatAiApplication) getApplicationContext()).getAppContainer().getChatAiServerManager();
        }
        return null;
    }

    private Notification buildLegacyNotification(String text) {
        Intent settingsIntent = new Intent(this, SettingsActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 201, settingsIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(getString(R.string.wireless_adb_title))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Wireless ADB",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Wireless Debugging background discovery");
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopPairingDiscoveryInternal(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
