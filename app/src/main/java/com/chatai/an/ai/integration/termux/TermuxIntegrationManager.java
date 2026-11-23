package com.chatai.an.ai.integration.termux;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.domain.model.IntegrationStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real Termux integration using Termux RUN_COMMAND Intent API.
 * Strictly checks installation, RunCommandService resolution, RUN_COMMAND permission,
 * and live probe results before reporting ACTIVE.
 */
public class TermuxIntegrationManager {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    public static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    public static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    public static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    public static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    public static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    public static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    public static final String TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    public static class TermuxState {
        public final IntegrationStatus status;
        public final boolean installed;
        public final boolean serviceResolved;
        public final boolean permissionGranted;
        public final boolean verifiedRoundTrip;
        public final String detail;

        public TermuxState(
                IntegrationStatus status,
                boolean installed,
                boolean serviceResolved,
                boolean permissionGranted,
                boolean verifiedRoundTrip,
                String detail
        ) {
            this.status = status;
            this.installed = installed;
            this.serviceResolved = serviceResolved;
            this.permissionGranted = permissionGranted;
            this.verifiedRoundTrip = verifiedRoundTrip;
            this.detail = detail;
        }
    }

    public static class CommandResult {
        public final boolean launched;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final int internalErrCode;
        public final String internalErrMsg;
        public final boolean timedOut;

        public CommandResult(
                boolean launched,
                int exitCode,
                String stdout,
                String stderr,
                int internalErrCode,
                String internalErrMsg,
                boolean timedOut
        ) {
            this.launched = launched;
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.internalErrCode = internalErrCode;
            this.internalErrMsg = internalErrMsg != null ? internalErrMsg : "";
            this.timedOut = timedOut;
        }

        public boolean isSuccess() {
            return launched && !timedOut && internalErrCode == 0 && exitCode == 0;
        }
    }

    private static final ConcurrentHashMap<Integer, CompletableFuture<Bundle>> PENDING_CALLS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_EXEC_ID = new AtomicInteger(4000);

    private final Context context;
    private final MutableLiveData<TermuxState> stateLiveData = new MutableLiveData<>();
    private volatile boolean lastRoundTripVerified = false;
    private volatile String lastProbeError = "";

    public TermuxIntegrationManager(Context context) {
        this.context = context.getApplicationContext();
        refreshState();
    }

    public static void deliverResult(int execId, Bundle bundle) {
        CompletableFuture<Bundle> future = PENDING_CALLS.remove(execId);
        if (future != null) {
            future.complete(bundle != null ? bundle : new Bundle());
        }
    }

    public LiveData<TermuxState> observeState() {
        return stateLiveData;
    }

    public TermuxState refreshState() {
        TermuxState current = inspectState();
        stateLiveData.postValue(current);
        return current;
    }

    public TermuxState inspectState() {
        PackageManager pm = context.getPackageManager();
        boolean installed = isPackageInstalled(pm, TERMUX_PACKAGE);
        if (!installed) {
            lastRoundTripVerified = false;
            return new TermuxState(
                    IntegrationStatus.NOT_INSTALLED,
                    false,
                    false,
                    false,
                    false,
                    "Termux (com.termux) is not installed on this device."
            );
        }

        Intent probeIntent = new Intent(ACTION_RUN_COMMAND);
        probeIntent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        ResolveInfo resolved = pm.resolveService(probeIntent, 0);
        boolean serviceResolved = resolved != null;

        boolean permGranted = ContextCompat.checkSelfPermission(context, PERMISSION_RUN_COMMAND)
                == PackageManager.PERMISSION_GRANTED;

        if (!permGranted) {
            lastRoundTripVerified = false;
            return new TermuxState(
                    IntegrationStatus.PERMISSION_REQUIRED,
                    true,
                    serviceResolved,
                    false,
                    false,
                    "Termux installed, but RUN_COMMAND permission is not granted."
            );
        }

        if (!serviceResolved) {
            return new TermuxState(
                    IntegrationStatus.UNAVAILABLE,
                    true,
                    false,
                    true,
                    false,
                    "Termux RunCommandService did not resolve. Update Termux (>= 0.109)."
            );
        }

        if (!lastProbeError.isEmpty() && !lastRoundTripVerified) {
            return new TermuxState(
                    IntegrationStatus.ERROR,
                    true,
                    true,
                    true,
                    false,
                    lastProbeError
            );
        }

        if (lastRoundTripVerified) {
            return new TermuxState(
                    IntegrationStatus.ACTIVE,
                    true,
                    true,
                    true,
                    true,
                    "Termux RUN_COMMAND API verified & active."
            );
        }

        return new TermuxState(
                IntegrationStatus.CONNECTED,
                true,
                true,
                true,
                false,
                "Termux detected & permitted. Tap Test Connection to verify allow-external-apps=true."
        );
    }

    public TermuxState testConnectionSync() {
        TermuxState base = inspectState();
        if (!base.installed || !base.permissionGranted || !base.serviceResolved) {
            stateLiveData.postValue(base);
            return base;
        }
        CommandResult res = executeCommand("echo __CHATAI_TERMUX_OK__", TERMUX_HOME, 10);
        if (res.isSuccess() && res.stdout.contains("__CHATAI_TERMUX_OK__")) {
            lastRoundTripVerified = true;
            lastProbeError = "";
        } else {
            lastRoundTripVerified = false;
            if (res.timedOut) {
                lastProbeError = "Timed out waiting for Termux. Ensure allow-external-apps=true in ~/.termux/termux.properties and disable battery restriction for Termux.";
            } else if (!res.internalErrMsg.isEmpty()) {
                lastProbeError = res.internalErrMsg;
            } else if (!res.stderr.isEmpty()) {
                lastProbeError = res.stderr;
            } else {
                lastProbeError = "Termux did not acknowledge command (exit=" + res.exitCode + "). Check ~/.termux/termux.properties -> allow-external-apps=true.";
            }
        }
        return refreshState();
    }

    public CommandResult executeCommand(String command, String workdir, int timeoutSeconds) {
        TermuxState state = inspectState();
        if (!state.installed) {
            return new CommandResult(false, -1, "", "Termux is not installed.", -1, state.detail, false);
        }
        if (!state.permissionGranted) {
            return new CommandResult(false, -1, "", "Termux RUN_COMMAND permission is not granted.", -1, state.detail, false);
        }
        if (command == null || command.trim().isEmpty()) {
            return new CommandResult(false, -1, "", "Command cannot be empty.", -1, "Empty command", false);
        }

        int execId = NEXT_EXEC_ID.incrementAndGet();
        CompletableFuture<Bundle> future = new CompletableFuture<>();
        PENDING_CALLS.put(execId, future);

        try {
            Intent callbackIntent = new Intent(context, TermuxResultReceiver.class);
            callbackIntent.putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, execId);
            int piFlags = PendingIntent.FLAG_ONE_SHOT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, execId, callbackIntent, piFlags);

            Intent runIntent = new Intent(ACTION_RUN_COMMAND);
            runIntent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            runIntent.putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH);
            runIntent.putExtra(EXTRA_ARGUMENTS, new String[]{"-lc", command});
            runIntent.putExtra(EXTRA_WORKDIR, (workdir != null && !workdir.trim().isEmpty()) ? workdir.trim() : TERMUX_HOME);
            runIntent.putExtra(EXTRA_BACKGROUND, true);
            runIntent.putExtra(EXTRA_SESSION_ACTION, "0");
            runIntent.putExtra(EXTRA_PENDING_INTENT, pendingIntent);

            try {
                context.startService(runIntent);
            } catch (Exception se) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(runIntent);
                } else {
                    throw se;
                }
            }

            int timeout = Math.max(5, Math.min(120, timeoutSeconds));
            Bundle bundle = future.get(timeout, TimeUnit.SECONDS);

            String stdout = bundle.getString("stdout", "");
            String stderr = bundle.getString("stderr", "");
            int exitCode = bundle.getInt("exitCode", -1);
            int err = bundle.getInt("err", 0);
            String errmsg = bundle.getString("errmsg", "");

            if (err == 0 && exitCode == 0) {
                lastRoundTripVerified = true;
                lastProbeError = "";
                refreshState();
            } else if (err != 0) {
                lastProbeError = errmsg;
                refreshState();
            }
            return new CommandResult(true, exitCode, stdout, stderr, err, errmsg, false);
        } catch (TimeoutException te) {
            PENDING_CALLS.remove(execId);
            return new CommandResult(true, -1, "", "Termux command timed out after " + timeoutSeconds + "s. Ensure Termux has allow-external-apps=true and battery optimization disabled.", -1, "Timeout", true);
        } catch (SecurityException se) {
            PENDING_CALLS.remove(execId);
            lastRoundTripVerified = false;
            lastProbeError = "SecurityException: " + se.getMessage();
            refreshState();
            return new CommandResult(false, -1, "", se.getMessage(), -1, se.getMessage(), false);
        } catch (Exception e) {
            PENDING_CALLS.remove(execId);
            lastProbeError = e.getMessage() != null ? e.getMessage() : "Termux launch failed";
            refreshState();
            return new CommandResult(false, -1, "", lastProbeError, -1, lastProbeError, false);
        }
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
