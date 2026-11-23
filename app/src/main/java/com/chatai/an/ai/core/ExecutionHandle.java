package com.chatai.an.ai.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;

public class ExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Call> activeCall = new AtomicReference<>();

    public void attachCall(Call call) {
        activeCall.set(call);
        if (cancelled.get() && call != null) {
            call.cancel();
        }
    }

    public void clearCall() {
        activeCall.set(null);
    }

    public void cancel() {
        cancelled.set(true);
        Call call = activeCall.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
