package com.chatai.an.ai.integration.terminal;

import android.graphics.Color;
import android.text.SpannableStringBuilder;

import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.domain.model.TerminalBackend;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Interactive Terminal Session inspired by CodeOnTheGo's TerminalSession architecture.
 * Manages persistent cwd, exported env vars, command history, ANSI colorized scrollback,
 * active process stdin/SIGINT cancellation, and unified execution on App Terminal or Termux.
 */
public class TerminalSession {

    public static final int CONSOLE_BG_TEXT_COLOR = Color.parseColor("#E6EDF3");
    public static final int CONSOLE_MUTED_COLOR = Color.parseColor("#8B949E");
    public static final int CONSOLE_PROMPT_COLOR = Color.parseColor("#58A6FF");
    public static final int CONSOLE_TERMUX_COLOR = Color.parseColor("#3FB950");
    public static final int CONSOLE_SHIZUKU_COLOR = Color.parseColor("#A371F7");
    public static final int CONSOLE_STDERR_COLOR = Color.parseColor("#FF7B72");
    public static final int CONSOLE_SUCCESS_COLOR = Color.parseColor("#3FB950");
    public static final int CONSOLE_WARN_COLOR = Color.parseColor("#D29922");

    private static final int MAX_SCROLLBACK_CHARS = 120_000;

    public interface SessionListener {
        void onOutputChanged(CharSequence fullOutput);
        void onStateChanged(boolean running, String currentWorkdir, TerminalBackend backend);
    }

    private final AppTerminalManager appTerminalManager;
    private final TermuxIntegrationManager termuxManager;
    private final ShizukuIntegrationManager shizukuManager;

    private final SpannableStringBuilder scrollback = new SpannableStringBuilder();
    private final List<String> history = new ArrayList<>();
    private final Map<String, String> sessionEnv = new LinkedHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    private String appWorkdir;
    private String termuxWorkdir = TermuxIntegrationManager.TERMUX_HOME;
    private TerminalBackend activeBackend = TerminalBackend.APP_TERMINAL;
    private int historyCursor = -1;
    private SessionListener listener;

    public TerminalSession(
            AppTerminalManager appTerminalManager,
            TermuxIntegrationManager termuxManager,
            TerminalBackend initialBackend
    ) {
        this(appTerminalManager, termuxManager, null, initialBackend);
    }

    public TerminalSession(
            AppTerminalManager appTerminalManager,
            TermuxIntegrationManager termuxManager,
            ShizukuIntegrationManager shizukuManager,
            TerminalBackend initialBackend
    ) {
        this.appTerminalManager = appTerminalManager;
        this.termuxManager = termuxManager;
        this.shizukuManager = shizukuManager;
        this.activeBackend = initialBackend != null ? initialBackend : TerminalBackend.APP_TERMINAL;
        this.appWorkdir = appTerminalManager.resolveWorkingDirectory(null).getAbsolutePath();
        appendWelcomeBanner();
    }

    public void setListener(SessionListener listener) {
        this.listener = listener;
        notifyOutputChanged();
        notifyStateChanged();
    }

    public TerminalBackend getActiveBackend() {
        return activeBackend;
    }

    public void setActiveBackend(TerminalBackend backend) {
        TerminalBackend next = backend != null ? backend : TerminalBackend.APP_TERMINAL;
        if (this.activeBackend != next) {
            this.activeBackend = next;
            appendSystemLine("\n[Switched terminal backend -> " + next.getDefaultLabel() + "]\n", CONSOLE_WARN_COLOR);
            notifyStateChanged();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getCurrentWorkdir() {
        return activeBackend == TerminalBackend.TERMUX ? termuxWorkdir : appWorkdir;
    }

    public CharSequence getOutputSnapshot() {
        synchronized (scrollback) {
            return new SpannableStringBuilder(scrollback);
        }
    }

    public List<String> getHistorySnapshot() {
        synchronized (history) {
            return Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    public String navigateHistory(boolean previous) {
        synchronized (history) {
            if (history.isEmpty()) return "";
            if (previous) {
                if (historyCursor == -1) {
                    historyCursor = history.size() - 1;
                } else if (historyCursor > 0) {
                    historyCursor--;
                }
                return history.get(historyCursor);
            } else {
                if (historyCursor == -1) return "";
                if (historyCursor < history.size() - 1) {
                    historyCursor++;
                    return history.get(historyCursor);
                } else {
                    historyCursor = -1;
                    return "";
                }
            }
        }
    }

    /**
     * Completes a partial path/file token against files inside the current App Terminal directory.
     */
    public String completeInput(String currentInput) {
        if (currentInput == null) return "";
        int lastSpace = currentInput.lastIndexOf(' ');
        String prefixCmd = lastSpace >= 0 ? currentInput.substring(0, lastSpace + 1) : "";
        String token = lastSpace >= 0 ? currentInput.substring(lastSpace + 1) : currentInput;

        File baseDir = new File(appWorkdir);
        String dirPrefix = "";
        String filePrefix = token;
        int lastSlash = token.lastIndexOf('/');
        if (lastSlash >= 0) {
            dirPrefix = token.substring(0, lastSlash + 1);
            filePrefix = token.substring(lastSlash + 1);
            File candidate = token.startsWith("/") ? new File(dirPrefix) : new File(baseDir, dirPrefix);
            if (candidate.exists() && candidate.isDirectory()) {
                baseDir = candidate;
            }
        }

        File[] children = baseDir.listFiles();
        if (children == null) return currentInput;
        List<String> matches = new ArrayList<>();
        for (File child : children) {
            if (child.getName().startsWith(filePrefix)) {
                matches.add(child.getName() + (child.isDirectory() ? "/" : ""));
            }
        }
        if (matches.isEmpty()) return currentInput;
        Collections.sort(matches, String.CASE_INSENSITIVE_ORDER);
        return prefixCmd + dirPrefix + matches.get(0);
    }

    public void clearConsole() {
        synchronized (scrollback) {
            scrollback.clear();
        }
        appendWelcomeBanner();
    }

    public void sendStdinLine(String line) {
        Process proc = activeProcess.get();
        if (proc == null || !running.get()) return;
        try {
            OutputStream os = proc.getOutputStream();
            String payload = (line != null ? line : "") + "\n";
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
            appendSystemLine(payload, CONSOLE_BG_TEXT_COLOR);
        } catch (Exception e) {
            appendSystemLine("[stdin write failed: " + e.getMessage() + "]\n", CONSOLE_STDERR_COLOR);
        }
    }

    public void interrupt() {
        Process proc = activeProcess.getAndSet(null);
        if (proc != null) {
            AppTerminalManager.destroyProcessTree(proc);
            appendSystemLine("\n^C [Process interrupted]\n", CONSOLE_WARN_COLOR);
        }
    }

    public void submitCommand(String rawCommand, int timeoutSeconds) {
        String cmd = rawCommand != null ? rawCommand.trim() : "";
        if (cmd.isEmpty()) {
            return;
        }
        if (running.get()) {
            sendStdinLine(rawCommand);
            return;
        }

        synchronized (history) {
            if (history.isEmpty() || !history.get(history.size() - 1).equals(cmd)) {
                history.add(cmd);
            }
            historyCursor = -1;
        }

        appendPromptLine(cmd);

        if ("clear".equals(cmd) || "cls".equals(cmd)) {
            clearConsole();
            return;
        }
        if ("help".equals(cmd)) {
            appendHelpMessage();
            return;
        }

        if (activeBackend == TerminalBackend.APP_TERMINAL && handleInlineEnvCommand(cmd)) {
            notifyStateChanged();
            return;
        }

        running.set(true);
        notifyStateChanged();
        long startMs = System.currentTimeMillis();

        try {
            if (activeBackend == TerminalBackend.TERMUX) {
                executeViaTermux(cmd, timeoutSeconds, startMs);
            } else if (activeBackend == TerminalBackend.SHIZUKU) {
                executeViaShizuku(cmd, timeoutSeconds, startMs);
            } else {
                executeViaAppTerminal(cmd, timeoutSeconds, startMs);
            }
        } finally {
            activeProcess.set(null);
            running.set(false);
            notifyStateChanged();
        }
    }

    private void executeViaAppTerminal(String cmd, int timeoutSeconds, long startMs) {
        AppTerminalManager.CommandResult res = appTerminalManager.executeCommandStreaming(
                cmd,
                appWorkdir,
                timeoutSeconds,
                new LinkedHashMap<>(sessionEnv),
                new AppTerminalManager.StreamListener() {
                    @Override
                    public void onProcessStarted(Process process) {
                        activeProcess.set(process);
                    }

                    @Override
                    public void onStdoutChunk(String chunk) {
                        appendAnsiChunk(chunk, CONSOLE_BG_TEXT_COLOR);
                    }

                    @Override
                    public void onStderrChunk(String chunk) {
                        appendAnsiChunk(chunk, CONSOLE_STDERR_COLOR);
                    }
                }
        );

        if (res.workdir != null && !res.workdir.isEmpty()) {
            appWorkdir = res.workdir;
        }

        if (!res.launched && !res.errorMessage.isEmpty()) {
            appendSystemLine(res.errorMessage + "\n", CONSOLE_STDERR_COLOR);
        } else if (res.timedOut) {
            appendSystemLine("\n[timed out after " + timeoutSeconds + "s]\n", CONSOLE_WARN_COLOR);
        }

        long elapsed = System.currentTimeMillis() - startMs;
        int exitColor = res.exitCode == 0 ? CONSOLE_MUTED_COLOR : CONSOLE_STDERR_COLOR;
        appendSystemLine("[exit " + res.exitCode + " • " + elapsed + "ms]\n", exitColor);
    }

    private void executeViaTermux(String cmd, int timeoutSeconds, long startMs) {
        if (termuxManager == null) {
            appendSystemLine("Termux manager is unavailable.\n", CONSOLE_STDERR_COLOR);
            return;
        }
        TermuxIntegrationManager.CommandResult res = termuxManager.executeCommand(cmd, termuxWorkdir, timeoutSeconds);
        if (!res.launched) {
            appendSystemLine(
                    (res.internalErrMsg.isEmpty() ? res.stderr : res.internalErrMsg) + "\n",
                    CONSOLE_STDERR_COLOR
            );
            return;
        }
        if (!res.stdout.isEmpty()) {
            String normalized = res.stdout.endsWith("\n") ? res.stdout : (res.stdout + "\n");
            appendAnsiChunk(normalized, CONSOLE_BG_TEXT_COLOR);
        }
        if (!res.stderr.isEmpty()) {
            String normalized = res.stderr.endsWith("\n") ? res.stderr : (res.stderr + "\n");
            appendAnsiChunk(normalized, CONSOLE_STDERR_COLOR);
        }
        if (res.timedOut) {
            appendSystemLine("[Termux timed out]\n", CONSOLE_WARN_COLOR);
        }
        long elapsed = System.currentTimeMillis() - startMs;
        int exitColor = res.isSuccess() ? CONSOLE_TERMUX_COLOR : CONSOLE_STDERR_COLOR;
        appendSystemLine("[termux exit " + res.exitCode + " • " + elapsed + "ms]\n", exitColor);
    }

    private void executeViaShizuku(String cmd, int timeoutSeconds, long startMs) {
        if (shizukuManager == null) {
            appendSystemLine("Shizuku manager is unavailable.\n", CONSOLE_STDERR_COLOR);
            return;
        }
        try {
            ShizukuIntegrationManager.ShellResult res = shizukuManager.executeShell(cmd, appWorkdir, timeoutSeconds);
            if (!res.stdout.isEmpty()) {
                String normalized = res.stdout.endsWith("\n") ? res.stdout : (res.stdout + "\n");
                appendAnsiChunk(normalized, CONSOLE_BG_TEXT_COLOR);
            }
            if (!res.stderr.isEmpty()) {
                String normalized = res.stderr.endsWith("\n") ? res.stderr : (res.stderr + "\n");
                appendAnsiChunk(normalized, CONSOLE_STDERR_COLOR);
            }
            if (res.timedOut) {
                appendSystemLine("[Shizuku command timed out]\n", CONSOLE_WARN_COLOR);
            }
            long elapsed = System.currentTimeMillis() - startMs;
            int exitColor = res.isSuccess() ? CONSOLE_SHIZUKU_COLOR : CONSOLE_STDERR_COLOR;
            String uidTag = res.uid == 0 ? "root" : ("uid " + res.uid);
            appendSystemLine("[shizuku(" + uidTag + ") exit " + res.exitCode + " • " + elapsed + "ms]\n", exitColor);
        } catch (Exception e) {
            appendSystemLine((e.getMessage() != null ? e.getMessage() : "Shizuku execution failed") + "\n", CONSOLE_STDERR_COLOR);
        }
    }

    private boolean handleInlineEnvCommand(String cmd) {
        if (cmd.startsWith("export ") && !cmd.contains(";") && !cmd.contains("&&") && !cmd.contains("|")) {
            String expr = cmd.substring("export ".length()).trim();
            int eq = expr.indexOf('=');
            if (eq > 0) {
                String key = expr.substring(0, eq).trim();
                String val = unquote(expr.substring(eq + 1).trim());
                if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    sessionEnv.put(key, val);
                    appendSystemLine("[exported " + key + "]\n", CONSOLE_MUTED_COLOR);
                    return true;
                }
            }
        } else if (cmd.startsWith("unset ") && !cmd.contains(";") && !cmd.contains("&&")) {
            String key = cmd.substring("unset ".length()).trim();
            if (!key.isEmpty()) {
                sessionEnv.remove(key);
                appendSystemLine("[unset " + key + "]\n", CONSOLE_MUTED_COLOR);
                return true;
            }
        }
        return false;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private void appendWelcomeBanner() {
        AppTerminalManager.TerminalState state = appTerminalManager.inspectState();
        synchronized (scrollback) {
            AnsiTextParser.appendColored(scrollback, "ChatAI Terminal", CONSOLE_PROMPT_COLOR, true);
            AnsiTextParser.appendColored(scrollback, " • CodeOnTheGo-inspired Shell Console\n", CONSOLE_MUTED_COLOR, false);
            AnsiTextParser.appendColored(scrollback, "Workspace: " + appWorkdir + "\n", CONSOLE_MUTED_COLOR, false);
            AnsiTextParser.appendColored(
                    scrollback,
                    "Backend: " + activeBackend.getDefaultLabel()
                            + (state.shellPath.isEmpty() ? "" : " (" + state.shellPath + ")")
                            + " • Type 'help' or run a command.\n",
                    CONSOLE_MUTED_COLOR,
                    false
            );
        }
        notifyOutputChanged();
    }

    private void appendHelpMessage() {
        String help = "\nBuilt-in commands:\n"
                + "  help         Show this help summary\n"
                + "  clear / cls  Clear terminal scrollback\n"
                + "  cd <dir>     Change working directory (persists across commands)\n"
                + "  export K=V   Export environment variable for this session\n"
                + "  unset K      Remove exported environment variable\n"
                + "Tips:\n"
                + "  - Switch between 'App' and 'Termux' at the top bar anytime.\n"
                + "  - Tap TAB for filename completion, ↑/↓ for command history, CTRL+C to stop.\n";
        appendSystemLine(help, CONSOLE_MUTED_COLOR);
    }

    private void appendPromptLine(String cmd) {
        synchronized (scrollback) {
            if (scrollback.length() > 0 && scrollback.charAt(scrollback.length() - 1) != '\n') {
                scrollback.append('\n');
            }
            String prefix;
            int promptColor;
            if (activeBackend == TerminalBackend.TERMUX) {
                prefix = "termux $ ";
                promptColor = CONSOLE_TERMUX_COLOR;
            } else if (activeBackend == TerminalBackend.SHIZUKU) {
                prefix = "shizuku # ";
                promptColor = CONSOLE_SHIZUKU_COLOR;
            } else {
                prefix = shortenDir(appWorkdir) + " $ ";
                promptColor = CONSOLE_PROMPT_COLOR;
            }
            AnsiTextParser.appendColored(scrollback, prefix, promptColor, true);
            AnsiTextParser.appendColored(scrollback, cmd + "\n", CONSOLE_BG_TEXT_COLOR, true);
            trimScrollbackIfNeeded();
        }
        notifyOutputChanged();
    }

    private void appendAnsiChunk(String chunk, int defaultColor) {
        synchronized (scrollback) {
            AnsiTextParser.appendAnsiStyled(scrollback, chunk, defaultColor);
            trimScrollbackIfNeeded();
        }
        notifyOutputChanged();
    }

    private void appendSystemLine(String text, int color) {
        synchronized (scrollback) {
            AnsiTextParser.appendColored(scrollback, text, color, false);
            trimScrollbackIfNeeded();
        }
        notifyOutputChanged();
    }

    private void trimScrollbackIfNeeded() {
        if (scrollback.length() > MAX_SCROLLBACK_CHARS) {
            int excess = scrollback.length() - (int) (MAX_SCROLLBACK_CHARS * 0.8);
            int newline = -1;
            for (int i = excess; i < scrollback.length(); i++) {
                if (scrollback.charAt(i) == '\n') {
                    newline = i + 1;
                    break;
                }
            }
            scrollback.delete(0, newline > 0 ? newline : excess);
        }
    }

    private static String shortenDir(String path) {
        if (path == null || path.isEmpty()) return "~";
        int idx = path.lastIndexOf('/');
        if (idx >= 0 && idx < path.length() - 1) {
            String tail = path.substring(idx + 1);
            if (!tail.isEmpty()) return tail;
        }
        return path;
    }

    private void notifyOutputChanged() {
        if (listener != null) {
            listener.onOutputChanged(getOutputSnapshot());
        }
    }

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onStateChanged(running.get(), getCurrentWorkdir(), activeBackend);
        }
    }
}
