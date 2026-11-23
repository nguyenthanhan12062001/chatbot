package com.chatai.an.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class AppExecutors {
    private static volatile AppExecutors instance;

    private final Executor diskIO;
    private final Executor worker;
    private final Handler mainHandler;

    private AppExecutors() {
        this.diskIO = Executors.newSingleThreadExecutor();
        this.worker = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static AppExecutors getInstance() {
        if (instance == null) {
            synchronized (AppExecutors.class) {
                if (instance == null) {
                    instance = new AppExecutors();
                }
            }
        }
        return instance;
    }

    public Executor diskIO() {
        return diskIO;
    }

    public Executor worker() {
        return worker;
    }

    public void postToMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }
}
