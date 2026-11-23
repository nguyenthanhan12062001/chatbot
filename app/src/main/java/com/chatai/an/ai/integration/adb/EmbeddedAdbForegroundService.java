package com.chatai.an.ai.integration.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.chatai.an.R;
import com.chatai.an.core.AppLogger;
import com.chatai.an.presentation.settings.IntegrationsHubActivity;

/**
 * Foreground Service for Embedded Wireless ADB.
 * Maintains persistent background TLS session to Wireless Debugging and automatically reconnects.
 */
public class EmbeddedAdbForegroundService extends Service {
    private static final String TAG = "EmbeddedAdbFgs";
    public static final String CHANNEL_ID = "embedded_adb_channel";
    public static final int NOTIF_ID = 9527;

    public static final String ACTION_START = "com.chatai.an.action.START_EMBEDDED_ADB";
    public static final String ACTION_STOP = "com.chatai.an.action.STOP_EMBEDDED_ADB";

    private EmbeddedAdbManager adbManager;
    private volatile boolean running = false;
    private Thread reconnectThread;
    private EmbeddedAdbManager.AdbSession activeSession;

    @Override
    public void onCreate() {
        super.onCreate();
        adbManager = new EmbeddedAdbManager(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIF_ID, buildNotification("Embedded Wireless ADB running (Connecting...)"));
        startConnectionLoop();
        return START_STICKY;
    }

    private void startConnectionLoop() {
        if (running) return;
        running = true;
        reconnectThread = new Thread(() -> {
            while (running) {
                try {
                    if (adbManager.hasSavedCredentials()) {
                        EmbeddedAdbManager.AdbSession session = adbManager.connect("127.0.0.1", 5555);
                        if (session.isConnected()) {
                            activeSession = session;
                            updateNotification("Embedded Wireless ADB Connected (UID 2000)");
                            AppLogger.i(TAG, "Embedded ADB connected successfully in background service.");

                            while (running && session.isConnected()) {
                                Thread.sleep(5000);
                            }
                            session.close();
                        } else {
                            updateNotification("Embedded Wireless ADB Reconnecting...");
                        }
                    } else {
                        updateNotification("Embedded Wireless ADB: Waiting for pairing");
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    AppLogger.e(TAG, "Embedded ADB connection loop error", e);
                    updateNotification("Embedded ADB connection error. Retrying...");
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "EmbeddedAdbReconnectThread");
        reconnectThread.start();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent hubIntent = new Intent(this, IntegrationsHubActivity.class);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent hubPi = PendingIntent.getActivity(this, 201, hubIntent, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(getString(R.string.hub_embedded_adb_title))
                .setContentText(text)
                .setContentIntent(hubPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Embedded Wireless ADB",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Maintains background ADB TLS session and automatic reconnection");
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
        if (activeSession != null) {
            activeSession.close();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
