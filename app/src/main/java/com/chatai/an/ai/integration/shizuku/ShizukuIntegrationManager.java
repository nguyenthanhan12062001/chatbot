package com.chatai.an.ai.integration.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.BuildConfig;
import com.chatai.an.domain.model.IntegrationStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Manages real Shizuku Binder lifecycle, permission checks, and UserService binding.
 * Explicitly distinguishes between UID 0 (Root), UID 2000 (ADB Shell), and disconnected states.
 */
public class ShizukuIntegrationManager {
    public static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";
    public static final int REQUEST_CODE_SHIZUKU = 8192;

    public enum PrivilegeLevel {
        NONE("None"),
        SHELL("ADB Shell (UID 2000)"),
        ROOT("Root (UID 0)"),
        OTHER("Privileged UID");

        private final String label;

        PrivilegeLevel(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static class ShizukuState {
        public final IntegrationStatus status;
        public final boolean appInstalled;
        public final boolean binderAlive;
        public final boolean permissionGranted;
        public final boolean userServiceBound;
        public final int serviceUid;
        public final PrivilegeLevel privilegeLevel;
        public final String detail;

        public ShizukuState(
                IntegrationStatus status,
                boolean appInstalled,
                boolean binderAlive,
                boolean permissionGranted,
                boolean userServiceBound,
                int serviceUid,
                PrivilegeLevel privilegeLevel,
                String detail
        ) {
            this.status = status;
            this.appInstalled = appInstalled;
            this.binderAlive = binderAlive;
            this.permissionGranted = permissionGranted;
            this.userServiceBound = userServiceBound;
            this.serviceUid = serviceUid;
            this.privilegeLevel = privilegeLevel;
            this.detail = detail;
        }
    }

    public static class ShellResult {
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

        public boolean isSuccess() {
            return !timedOut && exitCode == 0;
        }
    }

    private final Context context;
    private final MutableLiveData<ShizukuState> stateLiveData = new MutableLiveData<>();
    private final Shizuku.UserServiceArgs userServiceArgs;

    private volatile IShizukuShellService boundService;
    private volatile int lastKnownUid = -1;
    private volatile String lastError = "";
    private volatile CountDownLatch bindLatch;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null && service.pingBinder()) {
                boundService = IShizukuShellService.Stub.asInterface(service);
                try {
                    lastKnownUid = boundService.getUid();
                    lastError = "";
                } catch (Exception e) {
                    lastKnownUid = -1;
                    lastError = e.getMessage() != null ? e.getMessage() : "UID query failed";
                }
            } else {
                boundService = null;
                lastKnownUid = -1;
            }
            if (bindLatch != null) bindLatch.countDown();
            refreshState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            boundService = null;
            lastKnownUid = -1;
            refreshState();
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        lastError = "";
        refreshState();
        tryBindIfPermitted();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        boundService = null;
        lastKnownUid = -1;
        refreshState();
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                tryBindIfPermitted();
            }
            refreshState();
        }
    };

    public ShizukuIntegrationManager(Context context) {
        this.context = context.getApplicationContext();
        this.userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this.context.getPackageName(), ShizukuShellUserService.class.getName())
        )
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE);

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {
            // Safe when running under unit test/Robolectric without ShizukuProvider initialized
        }
        refreshState();
    }

    public LiveData<ShizukuState> observeState() {
        return stateLiveData;
    }

    public boolean isReady() {
        ShizukuState s = inspectState();
        return s.binderAlive && s.permissionGranted && s.userServiceBound;
    }

    public ShizukuState refreshState() {
        ShizukuState current = inspectState();
        stateLiveData.postValue(current);
        return current;
    }

    public ShizukuState inspectState() {
        boolean installed = isShizukuInstalled();
        boolean binderAlive = false;
        boolean permGranted = false;
        int shizukuServerUid = -1;

        try {
            binderAlive = Shizuku.pingBinder();
            if (binderAlive) {
                permGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                shizukuServerUid = Shizuku.getUid();
            }
        } catch (Throwable ignored) {
            binderAlive = false;
        }

        if (!installed && !binderAlive) {
            return new ShizukuState(
                    IntegrationStatus.NOT_INSTALLED,
                    false,
                    false,
                    false,
                    false,
                    -1,
                    PrivilegeLevel.NONE,
                    "Shizuku app (moe.shizuku.privileged.api) is not installed."
            );
        }

        if (!binderAlive) {
            return new ShizukuState(
                    IntegrationStatus.NOT_CONNECTED,
                    installed,
                    false,
                    false,
                    false,
                    -1,
                    PrivilegeLevel.NONE,
                    "Shizuku installed, but Shizuku service is not running. Start Shizuku via Wireless ADB or Root."
            );
        }

        if (!permGranted) {
            PrivilegeLevel serverPriv = mapUid(shizukuServerUid);
            return new ShizukuState(
                    IntegrationStatus.PERMISSION_REQUIRED,
                    installed,
                    true,
                    false,
                    false,
                    shizukuServerUid,
                    serverPriv,
                    "Shizuku service running (" + serverPriv.getLabel() + "), waiting for permission."
            );
        }

        boolean bound = boundService != null && boundService.asBinder().pingBinder();
        int effectiveUid = bound && lastKnownUid >= 0 ? lastKnownUid : shizukuServerUid;
        PrivilegeLevel priv = mapUid(effectiveUid);

        if (!lastError.isEmpty() && !bound) {
            return new ShizukuState(
                    IntegrationStatus.ERROR,
                    installed,
                    true,
                    true,
                    false,
                    effectiveUid,
                    priv,
                    "Shizuku UserService error: " + lastError
            );
        }

        if (bound) {
            return new ShizukuState(
                    IntegrationStatus.ACTIVE,
                    installed,
                    true,
                    true,
                    true,
                    effectiveUid,
                    priv,
                    "Active • Privilege: " + priv.getLabel()
            );
        }

        return new ShizukuState(
                IntegrationStatus.CONNECTED,
                installed,
                true,
                true,
                false,
                effectiveUid,
                priv,
                "Shizuku Binder connected (" + priv.getLabel() + "). Tap Bind / Test to start Shell UserService."
        );
    }

    public boolean requestPermission() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                tryBindIfPermitted();
                return true;
            }
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU);
            return true;
        } catch (Throwable t) {
            lastError = t.getMessage() != null ? t.getMessage() : "Shizuku permission request failed";
            refreshState();
            return false;
        }
    }

    public void tryBindIfPermitted() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                if (boundService == null || !boundService.asBinder().pingBinder()) {
                    bindLatch = new CountDownLatch(1);
                    Shizuku.bindUserService(userServiceArgs, serviceConnection);
                }
            }
        } catch (Throwable t) {
            lastError = t.getMessage() != null ? t.getMessage() : "Failed to bind Shizuku UserService";
            refreshState();
        }
    }

    public ShizukuState bindAndVerifySync() {
        try {
            if (!Shizuku.pingBinder()) {
                return refreshState();
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return refreshState();
            }
            if (boundService == null || !boundService.asBinder().pingBinder()) {
                bindLatch = new CountDownLatch(1);
                Shizuku.bindUserService(userServiceArgs, serviceConnection);
                bindLatch.await(4, TimeUnit.SECONDS);
            }
            if (boundService != null && boundService.asBinder().pingBinder()) {
                lastKnownUid = boundService.getUid();
                lastError = "";
            }
        } catch (Throwable t) {
            lastError = t.getMessage() != null ? t.getMessage() : "Bind failed";
        }
        return refreshState();
    }

    public ShellResult executeShell(String command, String workdir, int timeoutSeconds) throws Exception {
        ShizukuState state = inspectState();
        if (!state.binderAlive) {
            throw new IllegalStateException("Shizuku service is not running.");
        }
        if (!state.permissionGranted) {
            throw new SecurityException("Shizuku permission is not granted for ChatAI.");
        }
        if (boundService == null || !boundService.asBinder().pingBinder()) {
            bindAndVerifySync();
        }
        if (boundService == null || !boundService.asBinder().pingBinder()) {
            throw new IllegalStateException("Shizuku UserService failed to bind.");
        }
        Bundle b = boundService.executeShell(command, workdir, timeoutSeconds);
        int exitCode = b.getInt("exitCode", -1);
        String stdout = b.getString("stdout", "");
        String stderr = b.getString("stderr", "");
        boolean timedOut = b.getBoolean("timedOut", false);
        int uid = b.getInt("uid", lastKnownUid);
        return new ShellResult(exitCode, stdout, stderr, timedOut, uid);
    }

    public void cleanup() {
        try {
            if (boundService != null) {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true);
                boundService = null;
            }
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {
        }
    }

    private boolean isShizukuInstalled() {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static PrivilegeLevel mapUid(int uid) {
        if (uid == 0) return PrivilegeLevel.ROOT;
        if (uid == 2000) return PrivilegeLevel.SHELL;
        if (uid > 0) return PrivilegeLevel.OTHER;
        return PrivilegeLevel.NONE;
    }
}
