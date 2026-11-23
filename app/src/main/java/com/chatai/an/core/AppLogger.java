package com.chatai.an.core;

import android.util.Log;

import com.chatai.an.BuildConfig;

public final class AppLogger {
    private static final String PREFIX = "ChatAI_";

    private AppLogger() {
    }

    public static void d(String tag, String message) {
        if (BuildConfig.DEBUG) {
            Log.d(PREFIX + tag, message);
        }
    }

    public static void w(String tag, String message) {
        Log.w(PREFIX + tag, message);
    }

    public static void e(String tag, String message, Throwable throwable) {
        Log.e(PREFIX + tag, message, throwable);
    }
}
