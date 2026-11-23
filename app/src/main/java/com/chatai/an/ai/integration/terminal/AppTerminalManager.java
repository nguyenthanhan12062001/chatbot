package com.chatai.an.ai.integration.terminal;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.domain.model.IntegrationStatus;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Built-in In-App Terminal execution engine inspired by CodeOnTheGo's terminal architecture.
 * Executes real POSIX shell commands via Android's /system/bin/sh (or /bin/sh on JVM test hosts)
 * directly inside the app workspace without requiring external binaries or dynamic code downloads.
 */
public class AppTerminalManager {

    private static final int MAX_CAPTURE_BYTES = 256 * 1024;
    private static final int STREAM_CHUNK_BYTES = 2048;

    public static class TerminalState {
        public final IntegrationStatus status;
        public final String shellPath;
        public final String workspacePath;
        public final String homePath;
        public final String detail;

        public TerminalState(
                IntegrationStatus status,
                String shellPath,
                String workspacePath,
                String homePath,
                String detail
        ) {
            this.status = status;
            this.shellPath = shellPath;
            this.workspacePath = workspacePath;
            this.homePath = homePath;
            this.detail = detail;
        }
    }

    public static class CommandResult {
        public final boolean launched;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;
        public final String workdir;
        public final String errorMessage;

        public CommandResult(
                boolean launched,
                int exitCode,
                String stdout,
                String stderr,
                boolean timedOut,
                String workdir,
                String errorMessage
        ) {
            this.launched = launched;
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
            this.timedOut = timedOut;
            this.workdir = workdir != null ? workdir : "";
            this.errorMessage = errorMessage != null ? errorMessage : "";
        }

        public boolean isSuccess() {
            return launched && !timedOut && exitCode == 0;
        }
    }

    public interface StreamListener {
        void onProcessStarted(Process process);
        void onStdoutChunk(String chunk);
        void onStderrChunk(String chunk);
    }

    private final Context context;
    private final WorkspaceFileManager workspaceFileManager;
    private final MutableLiveData<TerminalState> stateLiveData = new MutableLiveData<>();

    public AppTerminalManager(Context context, WorkspaceFileManager workspaceFileManager) {
        this.context = context.getApplicationContext();
        this.workspaceFileManager = workspaceFileManager;
        refreshState();
    }

    public LiveData<TerminalState> observeState() {
        return stateLiveData;
    }

    public TerminalState refreshState() {
        TerminalState state = inspectState();
        stateLiveData.postValue(state);
        return state;
    }

    public TerminalState inspectState() {
        String shell = resolveShellExecutable();
        File workdir = resolveWorkingDirectory(null);
        File homeDir = ensureTerminalHomeDir();
        if (shell == null) {
            return new TerminalState(
                    IntegrationStatus.UNAVAILABLE,
                    "",
                    workdir.getAbsolutePath(),
                    homeDir.getAbsolutePath(),
                    "No POSIX shell (/system/bin/sh) available on this environment."
            );
        }
        return new TerminalState(
                IntegrationStatus.ACTIVE,
                shell,
                workdir.getAbsolutePath(),
                homeDir.getAbsolutePath(),
                "Built-in App Terminal ready (" + shell + ") • Dir: " + workdir.getAbsolutePath()
        );
    }

    public String resolveShellExecutable() {
        File androidSh = new File("/system/bin/sh");
        if (androidSh.exists() && androidSh.canExecute()) {
            return androidSh.getAbsolutePath();
        }
        File posixSh = new File("/bin/sh");
        if (posixSh.exists() && posixSh.canExecute()) {
            return posixSh.getAbsolutePath();
        }
        return androidSh.exists() ? androidSh.getAbsolutePath() : (posixSh.exists() ? posixSh.getAbsolutePath() : null);
    }

    public File ensureTerminalHomeDir() {
        File home = new File(context.getFilesDir(), "terminal_home");
        if (!home.exists()) {
            home.mkdirs();
        }
        return home;
    }

    public File resolveWorkingDirectory(String requestedWorkdir) {
        if (requestedWorkdir != null && !requestedWorkdir.trim().isEmpty()) {
            String trimmed = requestedWorkdir.trim();
            if (!trimmed.startsWith("saf://") && !trimmed.startsWith("content://")) {
                File f = new File(trimmed);
                if (f.exists() && f.isDirectory()) {
                    return f;
                }
            }
        }
        if (workspaceFileManager != null) {
            String active = workspaceFileManager.getActiveWorkspacePath();
            if (active != null && !active.isEmpty() && !active.startsWith("saf://") && !active.startsWith("content://")) {
                File f = new File(active);
                if (f.exists() && f.isDirectory()) {
                    return f;
                }
            }
            return workspaceFileManager.getDefaultWorkspaceDir();
        }
        return ensureTerminalHomeDir();
    }

    public Map<String, String> buildBaseEnvironment(File workdir, Map<String, String> extraEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        File home = ensureTerminalHomeDir();
        File tmp = context.getCacheDir();
        if (tmp != null && !tmp.exists()) {
            tmp.mkdirs();
        }
        String sysPath = System.getenv("PATH");
        if (sysPath == null || sysPath.trim().isEmpty()) {
            sysPath = "/system/bin:/system/xbin:/sbin:/vendor/bin";
        }
        env.put("PATH", sysPath);
        env.put("HOME", home.getAbsolutePath());
        if (tmp != null) {
            env.put("TMPDIR", tmp.getAbsolutePath());
        }
        env.put("PWD", workdir.getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("LANG", "C.UTF-8");
        if (workspaceFileManager != null) {
            env.put("CHATAI_WORKSPACE", workspaceFileManager.getActiveWorkspacePath());
        }
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
        return env;
    }

    public CommandResult executeCommand(String command, String workdir, int timeoutSeconds) {
        return executeCommandInternal(command, workdir, timeoutSeconds, Collections.emptyMap(), null);
    }

    public CommandResult executeCommandStreaming(
            String command,
            String workdir,
            int timeoutSeconds,
            Map<String, String> sessionEnv,
            StreamListener listener
    ) {
        return executeCommandInternal(command, workdir, timeoutSeconds, sessionEnv, listener);
    }

    private CommandResult executeCommandInternal(
            String command,
            String requestedWorkdir,
            int timeoutSeconds,
            Map<String, String> sessionEnv,
            StreamListener listener
    ) {
        if (command == null || command.trim().isEmpty()) {
            return new CommandResult(false, -1, "", "Command cannot be empty.", false, "", "Empty command");
        }
        String shell = resolveShellExecutable();
        if (shell == null) {
            return new CommandResult(false, -1, "", "Shell executable not found.", false, "", "No shell available");
        }

        File cwd = resolveWorkingDirectory(requestedWorkdir);
        int boundedTimeout = Math.max(5, Math.min(120, timeoutSeconds));

        File pwdTrackFile = null;
        String effectiveScript = command;
        try {
            File tmpDir = context.getCacheDir() != null ? context.getCacheDir() : cwd;
            pwdTrackFile = File.createTempFile("chatai_pwd_", ".tmp", tmpDir);
        } catch (IOException ignored) {
        }

        Map<String, String> mergedEnv = buildBaseEnvironment(cwd, sessionEnv);
        if (pwdTrackFile != null) {
            mergedEnv.put("__CHATAI_PWD_FILE", pwdTrackFile.getAbsolutePath());
            effectiveScript = "trap 'pwd > \"$__CHATAI_PWD_FILE\" 2>/dev/null' EXIT\n" + command;
        }

        ProcessBuilder pb = new ProcessBuilder(shell, "-c", effectiveScript);
        pb.directory(cwd);
        pb.environment().putAll(mergedEnv);

        Process process = null;
        try {
            process = pb.start();
            if (listener != null) {
                listener.onProcessStarted(process);
            }

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            final InputStream stdoutStream = process.getInputStream();
            final InputStream stderrStream = process.getErrorStream();

            Thread outReader = new Thread(() -> drainStream(stdoutStream, outBuf, listener, true), "AppTerm-Out");
            Thread errReader = new Thread(() -> drainStream(stderrStream, errBuf, listener, false), "AppTerm-Err");
            outReader.start();
            errReader.start();

            boolean finished = process.waitFor(boundedTimeout, TimeUnit.SECONDS);
            if (!finished) {
                destroyProcessTree(process);
                joinQuietly(outReader);
                joinQuietly(errReader);
                String out = AnsiTextParser.stripAnsi(outBuf.toString(StandardCharsets.UTF_8.name()));
                String err = AnsiTextParser.stripAnsi(errBuf.toString(StandardCharsets.UTF_8.name()));
                return new CommandResult(
                        true,
                        -1,
                        out,
                        err.isEmpty() ? "Command timed out after " + boundedTimeout + "s." : err,
                        true,
                        cwd.getAbsolutePath(),
                        "Timed out after " + boundedTimeout + "s"
                );
            }

            joinQuietly(outReader);
            joinQuietly(errReader);

            int exitCode = process.exitValue();
            String finalWorkdir = readTrackedWorkdir(pwdTrackFile, cwd.getAbsolutePath());
            String out = outBuf.toString(StandardCharsets.UTF_8.name());
            String err = errBuf.toString(StandardCharsets.UTF_8.name());

            return new CommandResult(true, exitCode, out, err, false, finalWorkdir, "");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (process != null) {
                destroyProcessTree(process);
            }
            return new CommandResult(true, 130, "", "Interrupted (SIGINT)", false, cwd.getAbsolutePath(), "Interrupted");
        } catch (Exception e) {
            if (process != null) {
                destroyProcessTree(process);
            }
            String msg = e.getMessage() != null ? e.getMessage() : "Shell launch failed";
            return new CommandResult(false, -1, "", msg, false, cwd.getAbsolutePath(), msg);
        } finally {
            if (pwdTrackFile != null && pwdTrackFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                pwdTrackFile.delete();
            }
        }
    }

    public static void destroyProcessTree(Process process) {
        if (process == null) return;
        try {
            process.destroy();
            if (!process.waitFor(150, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            try {
                process.destroyForcibly();
            } catch (Exception ignored2) {
            }
        }
    }

    private static String readTrackedWorkdir(File trackFile, String fallback) {
        if (trackFile == null || !trackFile.exists() || trackFile.length() == 0) {
            return fallback;
        }
        try {
            byte[] data = java.nio.file.Files.readAllBytes(trackFile.toPath());
            String line = new String(data, StandardCharsets.UTF_8).trim();
            if (!line.isEmpty()) {
                File dir = new File(line);
                if (dir.exists() && dir.isDirectory()) {
                    return dir.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static void drainStream(
            InputStream stream,
            ByteArrayOutputStream sink,
            StreamListener listener,
            boolean isStdout
    ) {
        byte[] buffer = new byte[STREAM_CHUNK_BYTES];
        int total = 0;
        try {
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (total < MAX_CAPTURE_BYTES) {
                    int toKeep = Math.min(n, MAX_CAPTURE_BYTES - total);
                    sink.write(buffer, 0, toKeep);
                    total += toKeep;
                }
                if (listener != null) {
                    String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                    if (isStdout) {
                        listener.onStdoutChunk(chunk);
                    } else {
                        listener.onStderrChunk(chunk);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
