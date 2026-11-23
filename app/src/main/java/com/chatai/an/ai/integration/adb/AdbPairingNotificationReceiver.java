package com.chatai.an.ai.integration.adb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.app.RemoteInput;

import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.core.AppExecutors;

/**
 * Handles inline RemoteInput PIN submission ("Enter pairing code") and cancel actions
 * from the Android Wireless Debugging pairing notification.
 */
public class AdbPairingNotificationReceiver extends BroadcastReceiver {

    public static final String ACTION_SUBMIT_PAIRING_CODE = "com.chatai.an.action.ADB_SUBMIT_PAIRING_CODE";
    public static final String ACTION_STOP_PAIRING = "com.chatai.an.action.ADB_STOP_PAIRING";
    public static final String EXTRA_HOST = "extra_host";
    public static final String EXTRA_PORT = "extra_port";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        Context appCtx = context.getApplicationContext();
        if (!(appCtx instanceof ChatAiApplication)) return;

        ChatAiServerManager serverManager = ((ChatAiApplication) appCtx).getAppContainer().getChatAiServerManager();
        String action = intent.getAction();

        if (ACTION_STOP_PAIRING.equals(action)) {
            serverManager.stopPairingDiscoveryService();
            serverManager.dismissPairingNotification();
            return;
        }

        if (ACTION_SUBMIT_PAIRING_CODE.equals(action)) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            if (results == null) return;
            CharSequence rawCode = results.getCharSequence(ChatAiServerManager.REMOTE_INPUT_KEY);
            String pin = rawCode != null ? rawCode.toString().trim() : "";
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, -1);

            PendingResult pending = goAsync();
            AppExecutors.getInstance().worker().execute(() -> {
                try {
                    serverManager.submitPairingCodeSync(host, port, pin);
                } finally {
                    pending.finish();
                }
            });
        }
    }
}
