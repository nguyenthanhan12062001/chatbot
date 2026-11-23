package com.chatai.an.ai.integration.shizuku;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Privileged Shizuku UserService extending android.app.Service and executing inside Shizuku's UID 2000 (ADB Shell) or UID 0 (Root) process.
 */
public class ShizukuShellUserService extends Service {

    private static final int MAX_OUTPUT_BYTES = 128 * 1024;

    private final IShizukuShellService.Stub binder = new IShizukuShellService.Stub() {
        @Override
        public void destroy() throws RemoteException {
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

                final java.lang.Process activeProc = proc;
                final StringBuilder outBuf = new StringBuilder();
                final StringBuilder errBuf = new StringBuilder();

                Thread tOut = new Thread(() -> readLimited(activeProc.getInputStream(), outBuf));
                Thread tErr = new Thread(() -> readLimited(activeProc.getErrorStream(), errBuf));
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
                    proc.destroyForcibly();
                }
                result.putInt("exitCode", -1);
                result.putString("stderr", e.getMessage() != null ? e.getMessage() : "Shell execution failed");
                return result;
            }
        }
    };

    public ShizukuShellUserService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
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
