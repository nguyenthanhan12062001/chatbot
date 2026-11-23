package com.chatai.an.ai.tool;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.TerminalBackend;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Central Tool Execution Engine managing:
 * - Registry lookup & Settings enablement enforcement
 * - Dynamic safety & permission confirmation gates (file overwrites/deletes, shell, installs, downloads)
 * - Real execution state tracking (REQUESTED -> WAITING_CONFIRMATION -> RUNNING -> COMPLETED/FAILED/TIMEOUT/CANCELLED)
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

    private final ToolRegistry registry;
    private final SettingsRepository settingsRepository;
    private final WorkspaceFileManager fileManager;
    private final AppExecutors appExecutors;
    private final ExecutorService toolPool = Executors.newCachedThreadPool();

    private final Map<String, ActiveToolExecution> executions = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ConcurrentHashMap<String, Future<?>> runningFutures = new ConcurrentHashMap<>();
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
        AppSettings settings = settingsRepository != null ? settingsRepository.getSettingsSnapshot() : null;
        String name = def.getName();

        // Explicit read-only vs mutating sub-actions for ADB multi-action tools
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

        // Dynamic overwrite protection for fs_write_file
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
            return resolved == TerminalBackend.TERMUX
                    ? "Execute command/script in Termux terminal backend"
                    : "Execute shell command/script in In-App Terminal";
        }
        if (toolName.startsWith("termux_")) return "Execute command/script inside Termux";
        if (toolName.startsWith("adb_")) return "Execute privileged Shizuku/ADB operation";
        if ("http_post".equals(toolName)) return "Send HTTP POST request to external endpoint";
        return "Execute privileged or mutating tool operation";
    }

    public ToolResult executeSync(
            String toolName,
            JsonObject args,
            ExecutionHandle handle,
            ConfirmationHandler confirmationHandler
    ) {
        String execId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        JsonObject safeArgs = args != null ? args : new JsonObject();
        String argsPreview = safeArgs.toString();
        if (argsPreview.length() > 360) argsPreview = argsPreview.substring(0, 360) + "...";

        AiTool tool = registry.getTool(toolName);
        if (tool == null) {
            ToolResult unknown = new ToolResult(toolName != null ? toolName : "", false, "", "Unregistered tool: " + toolName);
            record(new ActiveToolExecution(execId, String.valueOf(toolName), ToolCategory.UTILITY, argsPreview, ToolExecStatus.FAILED, unknown.getError(), start, System.currentTimeMillis()));
            return unknown;
        }

        ToolDefinition def = tool.getDefinition();
        AppSettings settings = settingsRepository != null ? settingsRepository.getSettingsSnapshot() : null;
        if (settings != null && !settings.isToolEnabled(def.getName())) {
            String msg = "Tool '" + def.getName() + "' is disabled in ChatAI Settings → Tool System.";
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.FAILED, msg, start, System.currentTimeMillis()));
            return new ToolResult(def.getName(), false, "", msg);
        }

        record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.REQUESTED, "Queued", start, start));

        if (handle != null && handle.isCancelled()) {
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.CANCELLED, "Cancelled", start, System.currentTimeMillis()));
            return new ToolResult(def.getName(), false, "", "Cancelled by user");
        }

        if (requiresConfirmation(tool, safeArgs)) {
            if (confirmationHandler == null) {
                String msg = "Tool '" + def.getName() + "' requires explicit user confirmation and no confirmation handler is available.";
                record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.FAILED, msg, start, System.currentTimeMillis()));
                return new ToolResult(def.getName(), false, "", msg);
            }
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.WAITING_CONFIRMATION, "Waiting for user approval", start, System.currentTimeMillis()));
            boolean approved = awaitApproval(execId, def.getName(), describeConfirmationReason(def.getName(), safeArgs), argsPreview, handle, confirmationHandler);
            if (!approved) {
                String msg = handle != null && handle.isCancelled() ? "Cancelled by user" : "Execution denied by user";
                ToolExecStatus st = handle != null && handle.isCancelled() ? ToolExecStatus.CANCELLED : ToolExecStatus.FAILED;
                record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, st, msg, start, System.currentTimeMillis()));
                return new ToolResult(def.getName(), false, "", msg);
            }
        }

        record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.RUNNING, "Executing...", start, System.currentTimeMillis()));

        int timeoutSec = settings != null ? settings.getToolTimeoutSeconds() : 30;
        Future<ToolResult> future = toolPool.submit(() -> tool.execute(safeArgs));
        runningFutures.put(execId, future);

        try {
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (!future.isDone()) {
                if (handle != null && handle.isCancelled()) {
                    future.cancel(true);
                    record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.CANCELLED, "Stopped by user", start, System.currentTimeMillis()));
                    return new ToolResult(def.getName(), false, "", "Tool execution stopped by user");
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    future.cancel(true);
                    throw new TimeoutException("Timed out after " + timeoutSec + "s");
                }
                try {
                    ToolResult res = future.get(Math.min(250L, remaining), TimeUnit.MILLISECONDS);
                    ToolExecStatus finalStatus = res.isSuccess() ? ToolExecStatus.COMPLETED : ToolExecStatus.FAILED;
                    record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, finalStatus,
                            res.isSuccess() ? "Success" : res.getError(), start, System.currentTimeMillis()));
                    return res;
                } catch (TimeoutException sliceTimeout) {
                    // Loop to poll handle.isCancelled() until overall deadline
                }
            }
            ToolResult res = future.get();
            ToolExecStatus finalStatus = res.isSuccess() ? ToolExecStatus.COMPLETED : ToolExecStatus.FAILED;
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, finalStatus,
                    res.isSuccess() ? "Success" : res.getError(), start, System.currentTimeMillis()));
            return res;
        } catch (TimeoutException te) {
            String msg = "Tool '" + def.getName() + "' timed out after " + timeoutSec + "s.";
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.TIMEOUT, msg, start, System.currentTimeMillis()));
            return new ToolResult(def.getName(), false, "", msg);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Tool execution error";
            record(new ActiveToolExecution(execId, def.getName(), def.getCategory(), argsPreview, ToolExecStatus.FAILED, msg, start, System.currentTimeMillis()));
            return new ToolResult(def.getName(), false, "", msg);
        } finally {
            runningFutures.remove(execId);
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
        final java.util.concurrent.atomic.AtomicBoolean approved = new java.util.concurrent.atomic.AtomicBoolean(false);
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
