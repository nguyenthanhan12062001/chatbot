package com.chatai.an.ai.tool;

import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * Real Shizuku & ADB Tool suite:
 * Shell, Package Manager, Activity Manager, Device Manager, Permission Manager,
 * Process Manager, Storage Manager, System Settings, and Logcat.
 */
public final class AdbShizukuTools {
    private AdbShizukuTools() {
    }

    public static void registerAll(ToolRegistry registry, ShizukuIntegrationManager shizuku) {
        registry.register(new AdbShellTool(shizuku));
        registry.register(new PackageManagerTool(shizuku));
        registry.register(new ActivityManagerTool(shizuku));
        registry.register(new DeviceManagerTool(shizuku));
        registry.register(new PermissionManagerTool(shizuku));
        registry.register(new ProcessManagerTool(shizuku));
        registry.register(new StorageManagerTool(shizuku));
        registry.register(new SystemSettingsTool(shizuku));
        registry.register(new LogcatTool(shizuku));
    }

    public static class AdbShellTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_shell",
                "Execute a general ADB Shell or Root command via Shizuku UserService (distinguishes UID 2000 Shell vs UID 0 Root).",
                "{\"type\":\"object\",\"required\":[\"command\"],\"properties\":{\"command\":{\"type\":\"string\"},\"workdir\":{\"type\":\"string\"},\"timeout_sec\":{\"type\":\"integer\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.ADB_SHIZUKU,
                true
        );
        private final ShizukuIntegrationManager shizuku;

        public AdbShellTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            return runShizukuCmd(shizuku, DEF.getName(), optStr(args, "command"), optStr(args, "workdir"), optInt(args, "timeout_sec", 25));
        }
    }

    public static class PackageManagerTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_package_manager",
                "Manage/inspect installed Android packages via Shizuku: list_packages, package_info, install_apk, uninstall_package.",
                "{\"type\":\"object\",\"required\":[\"action\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list_packages\",\"package_info\",\"install_apk\",\"uninstall_package\"]},\"package_name\":{\"type\":\"string\"},\"apk_path\":{\"type\":\"string\"},\"third_party_only\":{\"type\":\"boolean\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.ADB_SHIZUKU,
                true
        );
        private final ShizukuIntegrationManager shizuku;

        public PackageManagerTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            String pkg = optStr(args, "package_name");
            String apk = optStr(args, "apk_path");
            String cmd;
            switch (action) {
                case "list_packages":
                    boolean thirdOnly = args != null && args.has("third_party_only") && args.get("third_party_only").getAsBoolean();
                    cmd = thirdOnly ? "pm list packages -3" : "pm list packages";
                    break;
                case "package_info":
                    if (!isValidPkg(pkg)) return new ToolResult(DEF.getName(), false, "", "Invalid package_name");
                    cmd = "pm path " + pkg + " && dumpsys package " + pkg + " | grep -E 'versionCode|versionName|userId|firstInstallTime'";
                    break;
                case "install_apk":
                    if (apk.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing apk_path");
                    cmd = "pm install -r " + q(apk);
                    break;
                case "uninstall_package":
                    if (!isValidPkg(pkg)) return new ToolResult(DEF.getName(), false, "", "Invalid package_name");
                    cmd = "pm uninstall " + pkg;
                    break;
                default:
                    return new ToolResult(DEF.getName(), false, "", "Unsupported action: " + action);
            }
            return runShizukuCmd(shizuku, DEF.getName(), cmd, null, 35);
        }
    }

    public static class ActivityManagerTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_activity_manager",
                "Inspect or control activities via Shizuku: top_activity, start_activity, force_stop.",
                "{\"type\":\"object\",\"required\":[\"action\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"top_activity\",\"start_activity\",\"force_stop\"]},\"component\":{\"type\":\"string\"},\"package_name\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.ADB_SHIZUKU,
                true
        );
        private final ShizukuIntegrationManager shizuku;

        public ActivityManagerTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            String pkg = optStr(args, "package_name");
            String comp = optStr(args, "component");
            String cmd;
            switch (action) {
                case "top_activity":
                    cmd = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'";
                    break;
                case "start_activity":
                    if (!comp.isEmpty()) {
                        cmd = "am start -n " + q(comp);
                    } else if (isValidPkg(pkg)) {
                        cmd = "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1";
                    } else {
                        return new ToolResult(DEF.getName(), false, "", "Provide component or valid package_name");
                    }
                    break;
                case "force_stop":
                    if (!isValidPkg(pkg)) return new ToolResult(DEF.getName(), false, "", "Invalid package_name");
                    cmd = "am force-stop " + pkg;
                    break;
                default:
                    return new ToolResult(DEF.getName(), false, "", "Unsupported action: " + action);
            }
            return runShizukuCmd(shizuku, DEF.getName(), cmd, null, 20);
        }
    }

    public static class DeviceManagerTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_device_manager",
                "Read privileged device info via Shizuku: device_specs, battery, or properties.",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"device_specs\",\"battery\",\"properties\"]},\"prop_filter\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.ADB_SHIZUKU,
                false
        );
        private final ShizukuIntegrationManager shizuku;

        public DeviceManagerTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            if (action.isEmpty()) action = "device_specs";
            String cmd;
            switch (action) {
                case "battery":
                    cmd = "dumpsys battery";
                    break;
                case "properties":
                    String filter = optStr(args, "prop_filter");
                    cmd = filter.isEmpty() ? "getprop | head -n 80" : "getprop | grep -i " + q(filter);
                    break;
                case "device_specs":
                default:
                    cmd = "echo \"UID=$(id)\" && echo \"Android=$(getprop ro.build.version.release) SDK=$(getprop ro.build.version.sdk)\" && echo \"Model=$(getprop ro.product.manufacturer) $(getprop ro.product.model)\" && uname -a && head -n 5 /proc/meminfo && df -h /data";
                    break;
            }
            return runShizukuCmd(shizuku, DEF.getName(), cmd, null, 15);
        }
    }

    public static class PermissionManagerTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_permission_manager",
                "Inspect or manage app runtime permissions via Shizuku: check_permissions, grant, revoke.",
                "{\"type\":\"object\",\"required\":[\"action\",\"package_name\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"check_permissions\",\"grant\",\"revoke\"]},\"package_name\":{\"type\":\"string\"},\"permission\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.ADB_SHIZUKU,
                true
        );
        private final ShizukuIntegrationManager shizuku;

        public PermissionManagerTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            String pkg = optStr(args, "package_name");
            String perm = optStr(args, "permission");
            if (!isValidPkg(pkg)) return new ToolResult(DEF.getName(), false, "", "Invalid package_name");
            String cmd;
            switch (action) {
                case "check_permissions":
                    cmd = "dumpsys package " + pkg + " | grep -E 'permission\\.|granted=' | head -n 80";
                    break;
                case "grant":
                    if (perm.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing permission");
                    cmd = "pm grant " + pkg + " " + q(perm);
                    break;
                case "revoke":
                    if (perm.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing permission");
                    cmd = "pm revoke " + pkg + " " + q(perm);
                    break;
                default:
                    return new ToolResult(DEF.getName(), false, "", "Unsupported action: " + action);
            }
            return runShizukuCmd(shizuku, DEF.getName(), cmd, null, 20);
        }
    }

    public static class ProcessManagerTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_process_manager",
                "Inspect or manage processes via Shizuku: list_processes, kill_pid.",
                "{\"type\":\"object\",\"required\":[\"action\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list_processes\",\"kill_pid\"]},\"filter\":{\"type\":\"string\"},\"pid\":{\"type\":\"integer\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.ADB_SHIZUKU,
                true
        );
        private final ShizukuIntegrationManager shizuku;

        public ProcessManagerTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            switch (action) {
                case "list_processes":
                    String filter = optStr(args, "filter");
                    String cmd = filter.isEmpty() ? "ps -A -o PID,USER,RSS,NAME | head -n 80" : "ps -A -o PID,USER,RSS,NAME | grep -i " + q(filter);
                    return runShizukuCmd(shizuku, DEF.getName(), cmd, null, 15);
                case "kill_pid":
                    int pid = optInt(args, "pid", -1);
                    if (pid <= 1) return new ToolResult(DEF.getName(), false, "", "Invalid PID");
                    return runShizukuCmd(shizuku, DEF.getName(), "kill -TERM " + pid, null, 15);
                default:
                    return new ToolResult(DEF.getName(), false, "", "Unsupported action: " + action);
            }
        }
    }

    public static class StorageManagerTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_storage_manager",
                "Inspect storage directories/files via Shizuku shell: list_dir, stat, read_file.",
                "{\"type\":\"object\",\"required\":[\"action\",\"path\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list_dir\",\"stat\",\"read_file\"]},\"path\":{\"type\":\"string\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.ADB_SHIZUKU,
                false
        );
        private final ShizukuIntegrationManager shizuku;

        public StorageManagerTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            String path = optStr(args, "path");
            if (path.isEmpty()) return new ToolResult(DEF.getName(), false, "", "Missing path");
            String cmd;
            switch (action) {
                case "list_dir":
                    cmd = "ls -la " + q(path);
                    break;
                case "stat":
                    cmd = "stat " + q(path) + " && du -sh " + q(path);
                    break;
                case "read_file":
                    cmd = "head -c 65536 " + q(path);
                    break;
                default:
                    return new ToolResult(DEF.getName(), false, "", "Unsupported action: " + action);
            }
            return runShizukuCmd(shizuku, DEF.getName(), cmd, null, 15);
        }
    }

    public static class SystemSettingsTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_system_settings",
                "Read or update Android system/secure/global settings via Shizuku: get, put.",
                "{\"type\":\"object\",\"required\":[\"action\",\"namespace\",\"key\"],\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"get\",\"put\"]},\"namespace\":{\"type\":\"string\",\"enum\":[\"system\",\"secure\",\"global\"]},\"key\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}}}",
                ToolPermission.REQUIRE_CONFIRMATION,
                ToolCategory.ADB_SHIZUKU,
                true
        );
        private final ShizukuIntegrationManager shizuku;

        public SystemSettingsTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String action = optStr(args, "action").toLowerCase(Locale.ROOT);
            String ns = optStr(args, "namespace").toLowerCase(Locale.ROOT);
            String key = optStr(args, "key");
            if (!"system".equals(ns) && !"secure".equals(ns) && !"global".equals(ns)) {
                return new ToolResult(DEF.getName(), false, "", "Namespace must be system, secure, or global");
            }
            if (key.isEmpty() || !key.matches("[a-zA-Z0-9_.-]+")) {
                return new ToolResult(DEF.getName(), false, "", "Invalid setting key");
            }
            if ("get".equals(action)) {
                return runShizukuCmd(shizuku, DEF.getName(), "settings get " + ns + " " + key, null, 15);
            } else if ("put".equals(action)) {
                String value = optStr(args, "value");
                return runShizukuCmd(shizuku, DEF.getName(), "settings put " + ns + " " + key + " " + q(value), null, 15);
            }
            return new ToolResult(DEF.getName(), false, "", "Unsupported action: " + action);
        }
    }

    public static class LogcatTool implements AiTool {
        private static final ToolDefinition DEF = new ToolDefinition(
                "adb_logcat",
                "Read device Logcat logs via Shizuku with optional package_name, tag, level (V/D/I/W/E), and line count.",
                "{\"type\":\"object\",\"properties\":{\"package_name\":{\"type\":\"string\"},\"tag\":{\"type\":\"string\"},\"level\":{\"type\":\"string\",\"enum\":[\"V\",\"D\",\"I\",\"W\",\"E\"]},\"lines\":{\"type\":\"integer\"}}}",
                ToolPermission.AUTO_ALLOW,
                ToolCategory.ADB_SHIZUKU,
                false
        );
        private final ShizukuIntegrationManager shizuku;

        public LogcatTool(ShizukuIntegrationManager shizuku) {
            this.shizuku = shizuku;
        }

        @Override public ToolDefinition getDefinition() { return DEF; }

        @Override
        public ToolResult execute(JsonObject args) {
            String pkg = optStr(args, "package_name");
            String tag = optStr(args, "tag");
            String level = optStr(args, "level").toUpperCase(Locale.ROOT);
            int lines = Math.max(20, Math.min(400, optInt(args, "lines", 120)));

            StringBuilder cmd = new StringBuilder("logcat -d -t ").append(lines).append(" -v time");
            if (!tag.isEmpty()) {
                String lvl = ("V".equals(level) || "D".equals(level) || "I".equals(level) || "W".equals(level) || "E".equals(level)) ? level : "V";
                cmd.append(" ").append(q(tag + ":" + lvl)).append(" *:S");
            } else if ("W".equals(level) || "E".equals(level) || "I".equals(level) || "D".equals(level)) {
                cmd.append(" *:").append(level);
            }
            if (isValidPkg(pkg)) {
                cmd.append(" | grep -i ").append(q(pkg));
            }
            return runShizukuCmd(shizuku, DEF.getName(), cmd.toString(), null, 20);
        }
    }

    private static ToolResult runShizukuCmd(
            ShizukuIntegrationManager shizuku,
            String toolName,
            String command,
            String workdir,
            int timeoutSec
    ) {
        if (command == null || command.trim().isEmpty()) {
            return new ToolResult(toolName, false, "", "Command cannot be empty");
        }
        try {
            ShizukuIntegrationManager.ShellResult res = shizuku.executeShell(command, workdir, timeoutSec);
            String priv = res.uid == 0 ? "ROOT (UID 0)" : (res.uid == 2000 ? "SHELL (UID 2000)" : "UID " + res.uid);
            String out = "privilege=" + priv + " exitCode=" + res.exitCode
                    + "\n--- stdout ---\n" + (res.stdout.isEmpty() ? "(empty)" : res.stdout)
                    + (res.stderr.isEmpty() ? "" : ("\n--- stderr ---\n" + res.stderr));
            if (res.timedOut) {
                return new ToolResult(toolName, false, out, "Shizuku command timed out");
            }
            return new ToolResult(toolName, res.exitCode == 0, out, res.exitCode == 0 ? "" : ("Exit code " + res.exitCode));
        } catch (Exception e) {
            return new ToolResult(toolName, false, "", e.getMessage());
        }
    }

    private static boolean isValidPkg(String pkg) {
        return pkg != null && !pkg.isEmpty() && pkg.matches("[a-zA-Z0-9_.]+");
    }

    private static String q(String s) {
        return "'" + (s != null ? s.replace("'", "'\"'\"'") : "") + "'";
    }

    private static String optStr(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive()
                ? args.get(key).getAsString().trim()
                : "";
    }

    private static int optInt(JsonObject args, String key, int def) {
        if (args != null && args.has(key) && args.get(key).isJsonPrimitive()) {
            try { return args.get(key).getAsInt(); } catch (Exception ignored) {}
        }
        return def;
    }
}
