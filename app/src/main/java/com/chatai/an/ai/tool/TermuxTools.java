package com.chatai.an.ai.tool;

import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Termux execution tools using Termux RUN_COMMAND Intent API.
 */
public final class TermuxTools {
    private TermuxTools() {
    }

    public static void registerAll(ToolRegistry registry, TermuxIntegrationManager termux) {
        registry.register(new RunCommandTool(termux));
        registry.register(new RunScriptTool(termux));
    }

    public static class RunCommandTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "termux_run",
                "Run a shell command inside Termux (python, git, node, pip, npm, gcc, etc.). Returns exitCode, stdout, and stderr.",
                "{\"type\":\"object\",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"},\"workdir\":{\"type\":\"string\"},\"timeout_sec\":{\"type\":\"integer\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.TERMUX,
                true
        );
        private final TermuxIntegrationManager termux;

        public RunCommandTool(TermuxIntegrationManager termux) {
            this.termux = termux;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String cmd = optStr(args, "command");
            String workdir = optStr(args, "workdir");
            int timeout = optInt(args, "timeout_sec", 30);
            TermuxIntegrationManager.CommandResult res = termux.executeCommand(cmd, workdir, timeout);
            return formatResult(DEF.getName(), res);
        }
    }

    public static class RunScriptTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "termux_script",
                "Run a multi-line script in Termux using bash, python3, or node.",
                "{\"type\":\"object\",\"required\":[\"script\"],\"properties\":{\"interpreter\":{\"type\":\"string\",\"enum\":[\"bash\",\"python3\",\"node\",\"sh\"]},\"script\":{\"type\":\"string\"},\"workdir\":{\"type\":\"string\"},\"timeout_sec\":{\"type\":\"integer\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.TERMUX,
                true
        );
        private final TermuxIntegrationManager termux;

        public RunScriptTool(TermuxIntegrationManager termux) {
            this.termux = termux;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String interp = optStr(args, "interpreter").toLowerCase(Locale.ROOT);
            if (interp.isEmpty()) interp = "bash";
            if (!"bash".equals(interp) && !"python3".equals(interp) && !"node".equals(interp) && !"sh".equals(interp)) {
                return new ToolResult(DEF.getName(), false, "", "Unsupported interpreter: " + interp);
            }
            String script = optStr(args, "script");
            if (script.isEmpty()) {
                return new ToolResult(DEF.getName(), false, "", "Script cannot be empty");
            }
            String workdir = optStr(args, "workdir");
            int timeout = optInt(args, "timeout_sec", 45);
            String encoded = android.util.Base64.encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
            String cmd = "tmp=$(mktemp) && printf %s '" + encoded + "' | base64 -d > \"$tmp\" && " + interp + " \"$tmp\"; rc=$?; rm -f \"$tmp\"; exit $rc";
            TermuxIntegrationManager.CommandResult res = termux.executeCommand(cmd, workdir, timeout);
            return formatResult(DEF.getName(), res);
        }
    }

    private static ToolResult formatResult(String toolName, TermuxIntegrationManager.CommandResult res) {
        String out = "exitCode=" + res.exitCode
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
