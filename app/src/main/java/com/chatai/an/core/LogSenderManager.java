package com.chatai.an.core;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages exporting, sharing, and viewing application logs, build outputs, and diagnostics.
 */
public class LogSenderManager {

    private final Context context;

    public LogSenderManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File exportLogsToFile(String logsContent, String title) {
        try {
            File logsDir = new File(context.getCacheDir(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "ChatAI_" + (title != null ? title.replaceAll("\\s+", "_") : "Log") + "_" + timeStamp + ".txt";
            File logFile = new File(logsDir, fileName);

            try (FileWriter writer = new FileWriter(logFile)) {
                writer.write("=== ChatAI Diagnostic Log Export ===\n");
                writer.write("Timestamp: " + new Date() + "\n");
                writer.write("Device: " + android.os.Build.MODEL + " (API " + android.os.Build.VERSION.SDK_INT + ")\n");
                writer.write("========================================\n\n");
                writer.write(logsContent != null ? logsContent : "No log content available.");
            }

            return logFile;
        } catch (IOException e) {
            AppLogger.e("LogSenderManager", "Failed to export logs to file", e);
            return null;
        }
    }

    public void shareLogFile(File logFile) {
        if (logFile == null || !logFile.exists()) return;
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", logFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "ChatAI Build & Diagnostic Logs");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, "Share Logs via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            AppLogger.e("LogSenderManager", "Failed to share log file", e);
        }
    }
}
