package com.chatai.an.ai.tool;

import com.chatai.an.ai.integration.terminal.AnsiTextParser;
import com.chatai.an.ai.integration.terminal.AppTerminalManager;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.domain.model.TerminalBackend;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.SettingsRepository;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Unified Terminal execution tools supporting user-selectable backend:
 * - APP_TERMINAL: In-app /system/bin/sh workspace terminal
 * - TERMUX: External Termux RUN_COMMAND backend
 */
public final class TerminalTools {
    private TerminalTools() {
    }

    public static void registerAll(
            ToolRegistry registry,
            AppTerminalManager appTerminalManager,
            TermuxIntegrationManager termuxManager,
            SettingsRepository settingsRepository
    ) {
        registry.register(new TerminalRunTool(appTerminalManager, termuxManager, settingsRepository));
        registry.register(new TerminalScriptTool(appTerminalManager, termuxManager, settingsRepository));
    }

    public static TerminalBackend resolveTargetBackend(JsonObject args, SettingsRepository settingsRepository) {
        String explicit = optStr(args, "backend").toLowerCase(Locale.ROOT);
        if ("termux".equals(explicit)) {
            return TerminalBackend.TERMUX;
        }
        if ("app".equals(explicit) || "app_terminal".equals(explicit)) {
            return TerminalBackend.APP_TERMINAL;
        }
        if (settingsRepository != null && settingsRepository.getSettingsSnapshot() != null) {
            return settingsRepository.getSettingsSnapshot().getPreferredTerminalBackend();
        }
        return TerminalBackend.APP_TERMINAL;
    }

    public static class TerminalRunTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "terminal_run",
                "Run a shell command in the configured terminal backend (In-App Terminal or Termux, or explicit backend='app'|'termux'). Returns exitCode, stdout, stderr, and backend.",
                "{\"type\":\"object\",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"},\"workdir\":{\"type\":\"string\"},\"backend\":{\"type\":\"string\",\"enum\":[\"auto\",\"app\",\"termux\"]},\"timeout_sec\":{\"type\":\"integer\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.TERMINAL,
                true
        );

        private final AppTerminalManager appTerminal;
        private final TermuxIntegrationManager termux;
        private final SettingsRepository settingsRepository;

        public TerminalRunTool(
                AppTerminalManager appTerminal,
                TermuxIntegrationManager termux,
                SettingsRepository settingsRepository
        ) {
            this.appTerminal = appTerminal;
            this.termux = termux;
            this.settingsRepository = settingsRepository;
        }

        @Override
        public ToolDefinition getDefinition() {
            return DEF;
        }

        @Override
        public ToolResult execute(JsonObject args) {
            String cmd = optStr(args, "command");
            String workdir = optStr(args, "workdir");
            int timeout = optInt(args, "timeout_sec", 30);
            TerminalBackend backend = resolveTargetBackend(args, settingsRepository);

            if (backend == TerminalBackend.TERMUX) {
                TermuxIntegrationManager.CommandResult res = termux.executeCommand(cmd, workdir, timeout);
                return formatTermuxResult(DEF.getName(), res);
            }
            AppTerminalManager.CommandResult res = appTerminal.executeCommand(cmd, workdir, timeout);
            return formatAppResult(DEF.getName(), res);
        }
    }

    public static class TerminalScriptTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "terminal_script",
                "Execute a multi-line script in the configured terminal backend (In-App sh or Termux bash/python3/node). Supports backend='auto'|'app'|'termux'.",
                "{\"type\":\"object\",\"required\":[\"script\"],\"properties\":{\"interpreter\":{\"type\":\"string\",\"enum\":[\"sh\",\"bash\",\"python3\",\"node\"]},\"script\":{\"type\":\"string\"},\"workdir\":{\"type\":\"string\"},\"backend\":{\"type\":\"string\",\"enum\":[\"auto\",\"app\",\"termux\"]},\"timeout_sec\":{\"type\":\"integer\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.TERMINAL,
                true
        );

        private final AppTerminalManager appTerminal;
        private final TermuxIntegrationManager termux;
        private final SettingsRepository settingsRepository;

        public TerminalScriptTool(
                AppTerminalManager appTerminal,
                TermuxIntegrationManager termux,
                SettingsRepository settingsRepository
        ) {
            this.appTerminal = appTerminal;
            this.termux = termux;
            this.settingsRepository = settingsRepository;
        }

        @Override
        public ToolDefinition getDefinition() {
            return DEF;
        }

        @Override
        public ToolResult execute(JsonObject args) {
            String script = optStr(args, "script");
            if (script.trim().isEmpty()) {
                return new ToolResult(DEF.getName(), false, "", "Script cannot be empty");
            }
            String interp = optStr(args, "interpreter").toLowerCase(Locale.ROOT);
            String workdir = optStr(args, "workdir");
            int timeout = optInt(args, "timeout_sec", 45);
            TerminalBackend backend = resolveTargetBackend(args, settingsRepository);

            if (backend == TerminalBackend.TERMUX) {
                if (interp.isEmpty()) interp = "bash";
                if (!"bash".equals(interp) && !"python3".equals(interp) && !"node".equals(interp) && !"sh".equals(interp)) {
                    return new ToolResult(DEF.getName(), false, "", "Unsupported interpreter for Termux: " + interp);
                }
                String encoded = android.util.Base64.encodeToString(script.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                String cmd = "tmp=$(mktemp) && printf %s '" + encoded + "' | base64 -d > \"$tmp\" && " + interp + " \"$tmp\"; rc=$?; rm -f \"$tmp\"; exit $rc";
                TermuxIntegrationManager.CommandResult res = termux.executeCommand(cmd, workdir, timeout);
                return formatTermuxResult(DEF.getName(), res);
            }

            if (!interp.isEmpty() && !"sh".equals(interp)) {
                return new ToolResult(DEF.getName(), false, "", "In-App Terminal supports POSIX 'sh' only; switch backend to 'termux' for " + interp + ".");
            }
            AppTerminalManager.CommandResult res = appTerminal.executeCommand(script, workdir, timeout);
            return formatAppResult(DEF.getName(), res);
        }
    }

    private static ToolResult formatAppResult(String toolName, AppTerminalManager.CommandResult res) {
        String cleanOut = AnsiTextParser.stripAnsi(res.stdout);
        String cleanErr = AnsiTextParser.stripAnsi(res.stderr);
        String out = "backend=APP_TERMINAL"
                + "\nworkdir=" + res.workdir
                + "\nexitCode=" + res.exitCode
                + "\n--- stdout ---\n" + (cleanOut.isEmpty() ? "(empty)" : cleanOut)
                + "\n--- stderr ---\n" + (cleanErr.isEmpty() ? "(empty)" : cleanErr);
        if (!res.launched) {
            return new ToolResult(toolName, false, "", res.errorMessage.isEmpty() ? cleanErr : res.errorMessage);
        }
        if (res.timedOut) {
            return new ToolResult(toolName, false, out, "Command timed out");
        }
        return new ToolResult(toolName, res.exitCode == 0, out, res.exitCode == 0 ? "" : ("Command exited with code " + res.exitCode));
    }

    private static ToolResult formatTermuxResult(String toolName, TermuxIntegrationManager.CommandResult res) {
        String out = "backend=TERMUX"
                + "\nexitCode=" + res.exitCode
                + "\n--- stdout ---\n" + (res.stdout.isEmpty() ? "(empty)" : res.stdout)
                + "\n--- stderr ---\n" + (res.stderr.isEmpty() ? "(empty)" : res.stderr);
        if (!res.launched) {
            return new ToolResult(toolName, false, "", res.internalErrMsg.isEmpty() ? res.stderr : res.internalErrMsg);
        }
        if (res.timedOut) {
            return new ToolResult(toolName, false, out, "Command timed out");
        }
        if (res.internalErrCode != 0) {
            return new ToolResult(toolName, false, out, res.internalErrMsg);
        }
        return new ToolResult(toolName, res.exitCode == 0, out, res.exitCode == 0 ? "" : ("Command exited with code " + res.exitCode));
    }

    private static String optStr(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString()
                : "";
    }

    private static int optInt(JsonObject args, String key, int def) {
        if (args != null && args.has(key) && args.get(key).isJsonPrimitive()) {
            try { return args.get(key).getAsInt(); } catch (Exception ignored) {}
        }
        return def;
    }
}
