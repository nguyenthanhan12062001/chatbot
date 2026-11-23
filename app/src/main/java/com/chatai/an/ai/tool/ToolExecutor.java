package com.chatai.an.ai.tool;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.TerminalBackend;
import com.chatai.an.domain.model.ToolCallRecord;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.SettingsRepository;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central Tool Execution Engine managing:
 * - Registry lookup & Settings enablement enforcement
 * - Dynamic safety & permission confirmation gates + per-session Allow Always list
 * - Real-time ToolLifecycleListener callbacks emitting ToolCallRecord snapshots for inline UI cards
 * - Timeout & cancellation control
 */
public class ToolExecutor {

    public enum ToolExecStatus {
        REQUESTED,
        WAITING_CONFIRMATION,
        RUNNING,
        COMPLETED,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    public static class ActiveToolExecution {
        public final String executionId;
        public final String toolName;
        public final ToolCategory category;
        public final String argsPreview;
        public final ToolExecStatus status;
        public final String detail;
        public final long startedAt;
        public final long updatedAt;

        public ActiveToolExecution(
                String executionId,
                String toolName,
                ToolCategory category,
                String argsPreview,
                ToolExecStatus status,
                String detail,
                long startedAt,
                long updatedAt
        ) {
            this.executionId = executionId;
            this.toolName = toolName;
            this.category = category;
            this.argsPreview = argsPreview;
            this.status = status;
            this.detail = detail;
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
        }
    }

    public interface ConfirmationCallback {
        void onResult(boolean approved);
    }

    public interface ConfirmationHandler {
        void requestConfirmation(String executionId, String toolName, String reason, String argsPreview, ConfirmationCallback callback);
    }

    public interface ToolLifecycleListener {
        void onToolUpdated(ToolCallRecord record);
    }

    private final ToolRegistry registry;
    private final SettingsRepository settingsRepository;
    private final WorkspaceFileManager fileManager;
    private final AppExecutors appExecutors;
    private volatile ShizukuIntegrationManager shizukuManager;
    private final ExecutorService toolPool = Executors.newCachedThreadPool();

    private final Map<String, ActiveToolExecution> executions = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentHashMap<String, Future<?>> runningFutures = new ConcurrentHashMap<>();
    private final Set<String> sessionAllowedTools = ConcurrentHashMap.newKeySet();
    private final MutableLiveData<List<ActiveToolExecution>> activeExecutionsLiveData = new MutableLiveData<>(Collections.emptyList());

    public ToolExecutor(
            ToolRegistry registry,
            SettingsRepository settingsRepository,
            WorkspaceFileManager fileManager,
            AppExecutors appExecutors
    ) {
        this.registry = registry;
        this.settingsRepository = settingsRepository;
        this.fileManager = fileManager;
        this.appExecutors = appExecutors;
    }

    public void setShizukuManager(ShizukuIntegrationManager shizukuManager) {
        this.shizukuManager = shizukuManager;
    }

    public void allowToolForSession(String toolName) {
        if (toolName != null && !toolName.trim().isEmpty()) {
            sessionAllowedTools.add(toolName.trim().toLowerCase(Locale.ROOT));
        }
    }

    public boolean isAllowedForSession(String toolName) {
        return toolName != null && sessionAllowedTools.contains(toolName.trim().toLowerCase(Locale.ROOT));
    }

    public void clearSessionAllowlist() {
        sessionAllowedTools.clear();
    }

    public LiveData<List<ActiveToolExecution>> observeExecutions() {
        return activeExecutionsLiveData;
    }

    public void cancelExecution(String executionId) {
        if (executionId == null) return;
        Future<?> future = runningFutures.remove(executionId);
        if (future != null) {
            future.cancel(true);
        }
        ActiveToolExecution prev = executions.get(executionId);
        if (prev != null && (prev.status == ToolExecStatus.RUNNING || prev.status == ToolExecStatus.WAITING_CONFIRMATION)) {
            record(new ActiveToolExecution(
                    prev.executionId, prev.toolName, prev.category, prev.argsPreview,
                    ToolExecStatus.CANCELLED, "Stopped by user", prev.startedAt, System.currentTimeMillis()
            ));
        }
    }

    public boolean requiresConfirmation(AiTool tool, JsonObject args) {
        if (tool == null || tool.getDefinition() == null) return true;
        ToolDefinition def = tool.getDefinition();
        if (isAllowedForSession(def.getName())) {
            return false;
        }
        AppSettings settings = settingsRepository != null ? settingsRepository.getSettingsSnapshot() : null;
        String name = def.getName();

        if (name.startsWith("adb_") && !"adb_shell".equals(name)) {
            String action = args != null && args.has("action") ? args.get("action").getAsString().trim().toLowerCase(Locale.ROOT) : "";
            if ("list_packages".equals(action) || "package_info".equals(action)
                    || "top_activity".equals(action) || "device_specs".equals(action)
                    || "battery".equals(action) || "properties".equals(action)
                    || "check_permissions".equals(action) || "list_processes".equals(action)
                    || "list_dir".equals(action) || "stat".equals(action) || "read_file".equals(action)
                    || "get".equals(action) || "read".equals(action)) {
                return false;
            }
            return true;
        }

        if ("fs_write_file".equals(name)) {
            String path = args != null && args.has("path") ? args.get("path").getAsString().trim() : "";
            boolean append = args != null && args.has("append") && args.get("append").getAsBoolean();
            if (!append && fileManager != null && fileManager.isNonEmptyExistingFile(path)) {
                return settings == null || settings.isConfirmMutatingTools();
            }
            return false;
        }

        if ("fs_edit_file".equals(name)) {
            return settings == null || settings.isConfirmMutatingTools();
        }

        if (def.getCategory() == ToolCategory.TERMINAL
                || def.getCategory() == ToolCategory.TERMUX) {
            return false;
        }

        if (def.getCategory() == ToolCategory.ADB_SHIZUKU) {
            return settings == null || settings.isConfirmShellAndExternalTools() || def.getPermission() == ToolPermission.REQUIRE_CONFIRMATION;
        }

        if (def.getPermission() == ToolPermission.REQUIRE_CONFIRMATION) {
            if (!def.isMutating() && settings != null && !settings.isConfirmShellAndExternalTools()) {
                return false;
            }
            return settings == null || settings.isConfirmMutatingTools() || "fs_delete".equals(name) || "download_file".equals(name);
        }
        return false;
    }

    public String describeConfirmationReason(String toolName, JsonObject args) {
        if ("fs_delete".equals(toolName)) return "Delete file or directory from storage";
        if ("fs_write_file".equals(toolName)) return "Overwrite an existing non-empty file";
        if ("fs_edit_file".equals(toolName)) return "Modify existing file content";
        if ("download_file".equals(toolName)) return "Download external file from URL (no auto-execution/install)";
        if (toolName.startsWith("terminal_")) {
            TerminalBackend resolved = TerminalTools.resolveTargetBackend(args, settingsRepository);
            if (resolved == TerminalBackend.TERMUX) {
                return "Execute command/script in Termux terminal backend";
            }
            if (resolved == TerminalBackend.SHIZUKU) {
                return "Execute privileged command/script via Shizuku backend";
            }
            return "Execute shell command/script in In-App Terminal";
        }
        if (toolName.startsWith("termux_")) return "Execute command/script inside Termux";
        if (toolName.startsWith("adb_")) return "Execute privileged Shizuku/ADB operation";
        if ("http_post".equals(toolName)) return "Send HTTP POST request to external endpoint";
        return "Execute privileged or mutating tool operation";
    }

    public String resolvePrivilegeBadge(ToolDefinition def, JsonObject args) {
        if (def == null) return "Tool";
        ToolCategory cat = def.getCategory();
        if (cat == ToolCategory.ADB_SHIZUKU) {
            return formatShizukuPrivilege();
        }
        if (cat == ToolCategory.TERMINAL) {
            TerminalBackend b = TerminalTools.resolveTargetBackend(args, settingsRepository);
            if (b == TerminalBackend.SHIZUKU) return formatShizukuPrivilege();
            if (b == TerminalBackend.TERMUX) return "Termux";
            return "App Shell";
        }
        if (cat == ToolCategory.TERMUX) return "Termux";
        if (cat == ToolCategory.FILE_SYSTEM) return "Workspace";
        if (cat == ToolCategory.INTERNET || cat == ToolCategory.DOWNLOAD) return "Web";
        if (cat == ToolCategory.MEMORY) return "Memory";
        return cat.getLabel();
    }

    private String formatShizukuPrivilege() {
        if (shizukuManager != null) {
            ShizukuIntegrationManager.ShizukuState st = shizukuManager.inspectState();
            if (st.privilegeLevel == ShizukuIntegrationManager.PrivilegeLevel.ROOT) {
                return "Shizuku · Root";
            }
            if (st.privilegeLevel == ShizukuIntegrationManager.PrivilegeLevel.SHELL) {
                return "Shizuku · ADB";
            }
        }
        return "Shizuku";
    }

    public ToolResult executeSync(
            String toolName,
            JsonObject args,
            ExecutionHandle handle,
            ConfirmationHandler confirmationHandler
    ) {
        return executeSync(toolName, args, handle, confirmationHandler, null);
    }

    public ToolResult executeSync(
            String toolName,
            JsonObject args,
            ExecutionHandle handle,
            ConfirmationHandler confirmationHandler,
            ToolLifecycleListener lifecycleListener
    ) {
        String execId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        JsonObject safeArgs = args != null ? args : new JsonObject();
        String argsPreview = safeArgs.toString();
        if (argsPreview.length() > 8000) argsPreview = argsPreview.substring(0, 8000) + "...";

        AiTool tool = registry.getTool(toolName);
        if (tool == null) {
            ToolResult unknown = new ToolResult(toolName != null ? toolName : "", false, "", "Unregistered tool: " + toolName);
            record(new ActiveToolExecution(execId, String.valueOf(toolName), ToolCategory.UTILITY, argsPreview, ToolExecStatus.FAILED, unknown.getError(), start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(execId, String.valueOf(toolName), "Tool", ToolCallRecord.Status.FAILED, "", argsPreview, "", unknown.getError(), start, 0));
            return unknown;
        }

        ToolDefinition def = tool.getDefinition();
        String privilegeBadge = resolvePrivilegeBadge(def, safeArgs);
        String reason = describeConfirmationReason(def.getName(), safeArgs);
        AppSettings settings = settingsRepository != null ? settingsRepository.getSettingsSnapshot() : null;
        if (settings != null && !settings.isToolEnabled(def.getName())) {
            String msg = "Tool '" + def.getName() + "' is disabled in ChatAI Settings → Tool System.";
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.FAILED, msg, start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.FAILED, reason, argsPreview, "", msg, start, 0));
            return new ToolResult(def.getName(), false, "", msg);
        }

        record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.REQUESTED, "Queued", start, start));

        if (handle != null && handle.isCancelled()) {
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.CANCELLED, "Cancelled", start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.CANCELLED, reason, argsPreview, "", "Cancelled", start, 0));
            return new ToolResult(def.getName(), false, "", "Cancelled by user");
        }

        if (requiresConfirmation(tool, safeArgs)) {
            if (confirmationHandler == null) {
                String msg = "Tool '" + def.getName() + "' requires explicit user confirmation and no confirmation handler is available.";
                record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.FAILED, msg, start, System.currentTimeMillis()));
                emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.FAILED, reason, argsPreview, "", msg, start, 0));
                return new ToolResult(def.getName(), false, "", msg);
            }
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.WAITING_CONFIRMATION, "Waiting for user approval", start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.WAITING_APPROVAL, reason, argsPreview, "", "", start, 0));
            boolean approved = awaitApproval(execId, def.getName(), reason, argsPreview, handle, confirmationHandler);
            if (!approved) {
                boolean cancelled = handle != null && handle.isCancelled();
                String msg = cancelled ? "Cancelled by user" : "Execution denied by user";
                ToolExecStatus st = cancelled ? ToolExecStatus.CANCELLED : ToolExecStatus.FAILED;
                ToolCallRecord.Status recSt = cancelled ? ToolCallRecord.Status.CANCELLED : ToolCallRecord.Status.DENIED;
                record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, st, msg, start, System.currentTimeMillis()));
                emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, recSt, reason, argsPreview, "", msg, start, System.currentTimeMillis() - start));
                return new ToolResult(def.getName(), false, "", msg);
            }
        }

        long execStart = System.currentTimeMillis();
        record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.RUNNING, "Executing...", start, execStart));
        emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.RUNNING, reason, argsPreview, "", "", start, 0));

        int timeoutSec = settings != null ? settings.getToolTimeoutSeconds() : 30;
        Future<ToolResult> future = toolPool.submit(() -> tool.execute(safeArgs));
        runningFutures.put(execId, future);

        try {
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (!future.isDone()) {
                if (handle != null && handle.isCancelled()) {
                    future.cancel(true);
                    long dur = System.currentTimeMillis() - execStart;
                    record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.CANCELLED, "Stopped by user", start, System.currentTimeMillis()));
                    emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.CANCELLED, reason, argsPreview, "", "Stopped by user", start, dur));
                    return new ToolResult(def.getName(), false, "", "Tool execution stopped by user");
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    future.cancel(true);
                    throw new TimeoutException("Timed out after " + timeoutSec + "s");
                }
                try {
                    ToolResult res = future.get(Math.min(250L, remaining), TimeUnit.MILLISECONDS);
                    long dur = System.currentTimeMillis() - execStart;
                    ToolExecStatus finalStatus = res.isSuccess() ? ToolExecStatus.COMPLETED : ToolExecStatus.FAILED;
                    record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, finalStatus,
                            res.isSuccess() ? "Success" : res.getError(), start, System.currentTimeMillis()));
                    emitLifecycle(lifecycleListener, new ToolCallRecord(
                            execId, def.getName(), privilegeBadge,
                            res.isSuccess() ? ToolCallRecord.Status.COMPLETED : ToolCallRecord.Status.FAILED,
                            reason, argsPreview, truncateOutput(res.getOutput()), res.getError(), start, dur
                    ));
                    return res;
                } catch (TimeoutException sliceTimeout) {
                    // Loop to poll handle.isCancelled() until overall deadline
                }
            }
            ToolResult res = future.get();
            long dur = System.currentTimeMillis() - execStart;
            ToolExecStatus finalStatus = res.isSuccess() ? ToolExecStatus.COMPLETED : ToolExecStatus.FAILED;
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, finalStatus,
                    res.isSuccess() ? "Success" : res.getError(), start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(
                    execId, def.getName(), privilegeBadge,
                    res.isSuccess() ? ToolCallRecord.Status.COMPLETED : ToolCallRecord.Status.FAILED,
                    reason, argsPreview, truncateOutput(res.getOutput()), res.getError(), start, dur
            ));
            return res;
        } catch (TimeoutException te) {
            long dur = System.currentTimeMillis() - execStart;
            String msg = "Tool '" + def.getName() + "' timed out after " + timeoutSec + "s.";
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.TIMEOUT, msg, start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.FAILED, reason, argsPreview, "", msg, start, dur));
            return new ToolResult(def.getName(), false, "", msg);
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - execStart;
            String msg = e.getMessage() != null ? e.getMessage() : "Tool execution error";
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.FAILED, msg, start, System.currentTimeMillis()));
            emitLifecycle(lifecycleListener, new ToolCallRecord(execId, def.getName(), privilegeBadge, ToolCallRecord.Status.FAILED, reason, argsPreview, "", msg, start, dur));
            return new ToolResult(def.getName(), false, "", msg);
        } finally {
            runningFutures.remove(execId);
        }
    }

    private static String truncateOutput(String out) {
        if (out == null) return "";
        if (out.length() <= 12000) return out;
        return out.substring(0, 12000) + "\n...[output truncated]";
    }

    private static void emitLifecycle(ToolLifecycleListener listener, ToolCallRecord rec) {
        if (listener != null && rec != null) {
            listener.onToolUpdated(rec);
        }
    }

    private boolean awaitApproval(
            String executionId,
            String toolName,
            String reason,
            String argsPreview,
            ExecutionHandle handle,
            ConfirmationHandler handler
    ) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final AtomicBoolean approved = new AtomicBoolean(false);
        Runnable dispatch = () -> handler.requestConfirmation(executionId, toolName, reason, argsPreview, ok -> {
            approved.set(ok);
            latch.countDown();
        });
        if (appExecutors != null) {
            appExecutors.postToMain(dispatch);
        } else {
            dispatch.run();
        }
        try {
            while (!latch.await(200L, TimeUnit.MILLISECONDS)) {
                if (handle != null && handle.isCancelled()) {
                    return false;
                }
            }
            return approved.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void record(ActiveToolExecution exec) {
        synchronized (executions) {
            executions.put(exec.executionId, exec);
            while (executions.size() > 40) {
                String oldestKey = executions.keySet().iterator().next();
                executions.remove(oldestKey);
            }
            List<ActiveToolExecution> snapshot = new ArrayList<>(executions.values());
            Collections.reverse(snapshot);
            activeExecutionsLiveData.postValue(Collections.unmodifiableList(snapshot));
        }
    }
}
