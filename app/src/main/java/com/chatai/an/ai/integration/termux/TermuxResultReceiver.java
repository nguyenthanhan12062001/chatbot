package com.chatai.an.ai.integration.termux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Receives asynchronous PendingIntent execution bundles returned by Termux RunCommandService.
 */
public class TermuxResultReceiver extends BroadcastReceiver {
    public static final String EXTRA_EXECUTION_ID = "chatai_termux_exec_id";
    public static final String EXTRA_RESULT_BUNDLE = "result";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int execId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1);
        if (execId < 0) return;

        if (intent.getExtras() != null) {
            intent.getExtras().setClassLoader(context.getClassLoader());
        }
        Bundle resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE);
        if (resultBundle != null) {
            resultBundle.setClassLoader(context.getClassLoader());
        }
        TermuxIntegrationManager.deliverResult(execId, resultBundle);
    }
}
