package com.chatai.an.ai.integration.shizuku;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Privileged Shizuku UserService extending IShizukuShellService.Stub directly.
 * Spawned by Shizuku inside UID 2000 (ADB Shell) or UID 0 (Root) process via app_process.
 */
public class ShizukuShellUserService extends IShizukuShellService.Stub {

    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private final AtomicReference<java.lang.Process> activeProcess = new AtomicReference<>();

    @Keep
    public ShizukuShellUserService() {
    }

    @Keep
    public ShizukuShellUserService(Context context) {
        // Shizuku v13+ may invoke the (Context) constructor when instantiating UserService.
    }

    @Override
    public void destroy() throws RemoteException {
        java.lang.Process proc = activeProcess.getAndSet(null);
        if (proc != null) {
            try {
                proc.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
        System.exit(0);
    }

    @Override
    public int getUid() throws RemoteException {
        return Process.myUid();
    }

    @Override
    public Bundle executeShell(String command, String workdir, int timeoutSeconds) throws RemoteException {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        if (command == null || command.trim().isEmpty()) {
            result.putInt("exitCode", -1);
            result.putString("stderr", "Empty command");
            return result;
        }

        java.lang.Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            if (workdir != null && !workdir.trim().isEmpty()) {
                File dir = new File(workdir.trim());
                if (dir.exists() && dir.isDirectory()) {
                    pb.directory(dir);
                }
            }
            proc = pb.start();
            activeProcess.set(proc);

            final java.lang.Process runningProc = proc;
            final StringBuilder outBuf = new StringBuilder();
            final StringBuilder errBuf = new StringBuilder();

            Thread tOut = new Thread(() -> readLimited(runningProc.getInputStream(), outBuf));
            Thread tErr = new Thread(() -> readLimited(runningProc.getErrorStream(), errBuf));
            tOut.start();
            tErr.start();

            int timeout = Math.max(5, Math.min(120, timeoutSeconds));
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                tOut.join(500);
                tErr.join(500);
                result.putBoolean("timedOut", true);
                result.putInt("exitCode", -1);
                result.putString("stdout", outBuf.toString());
                result.putString("stderr", errBuf.toString() + "\n[Command timed out after " + timeout + "s]");
                return result;
            }

            tOut.join(1000);
            tErr.join(1000);
            result.putBoolean("timedOut", false);
            result.putInt("exitCode", proc.exitValue());
            result.putString("stdout", outBuf.toString());
            result.putString("stderr", errBuf.toString());
            return result;
        } catch (Exception e) {
            if (proc != null) {
                try {
                    proc.destroyForcibly();
                } catch (Throwable ignored) {
                }
            }
            result.putInt("exitCode", -1);
            result.putString("stderr", e.getMessage() != null ? e.getMessage() : "Shell execution failed");
            return result;
        } finally {
            activeProcess.compareAndSet(proc, null);
        }
    }

    private static void readLimited(InputStream is, StringBuilder dest) {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                int take = Math.min(n, MAX_OUTPUT_BYTES - total);
                if (take > 0) {
                    bos.write(buf, 0, take);
                    total += take;
                }
                if (total >= MAX_OUTPUT_BYTES) break;
            }
            dest.append(bos.toString(StandardCharsets.UTF_8.name()));
            if (total >= MAX_OUTPUT_BYTES) {
                dest.append("\n...[output truncated at 128 KB]");
            }
        } catch (Exception ignored) {
        }
    }
}
