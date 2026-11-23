package com.chatai.an.ai.integration;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.content.ContextCompat;

import com.chatai.an.ai.integration.download.DownloadToolManager;
import com.chatai.an.ai.integration.files.WorkspaceFileManager;
import com.chatai.an.ai.integration.net.InternetAccessManager;
import com.chatai.an.ai.integration.shizuku.ShizukuIntegrationManager;
import com.chatai.an.ai.integration.termux.TermuxIntegrationManager;
import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.IntegrationStatus;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.SettingsRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces real-time, honest diagnostic health items for every Settings Control Center subsystem.
 */
public class IntegrationDiagnosticsManager {

    public enum SubsystemId {
        PROVIDERS_MODELS,
        TOOL_SYSTEM,
        TERMUX,
        SHIZUKU,
        ADB_TOOLS,
        INTERNET_ACCESS,
        DOWNLOAD_MANAGEMENT,
        FILE_ACCESS,
        AGENT_SETTINGS,
        PERMISSION_MANAGEMENT
    }

    public static class SubsystemHealth {
        public final SubsystemId id;
        public final String title;
        public final IntegrationStatus status;
        public final String summary;

        public SubsystemHealth(SubsystemId id, String title, IntegrationStatus status, String summary) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.summary = summary;
        }
    }

    private final Context context;
    private final ModelRepository modelRepository;
    private final SettingsRepository settingsRepository;
    private final ToolRegistry toolRegistry;
    private final WorkspaceFileManager fileManager;
    private final TermuxIntegrationManager termuxManager;
    private final ShizukuIntegrationManager shizukuManager;
    private final InternetAccessManager internetManager;
    private final DownloadToolManager downloadManager;

    public IntegrationDiagnosticsManager(
            Context context,
            ModelRepository modelRepository,
            SettingsRepository settingsRepository,
            ToolRegistry toolRegistry,
            WorkspaceFileManager fileManager,
            TermuxIntegrationManager termuxManager,
            ShizukuIntegrationManager shizukuManager,
            InternetAccessManager internetManager,
            DownloadToolManager downloadManager
    ) {
        this.context = context.getApplicationContext();
        this.modelRepository = modelRepository;
        this.settingsRepository = settingsRepository;
        this.toolRegistry = toolRegistry;
        this.fileManager = fileManager;
        this.termuxManager = termuxManager;
        this.shizukuManager = shizukuManager;
        this.internetManager = internetManager;
        this.downloadManager = downloadManager;
    }

    public List<SubsystemHealth> inspectAll() {
        AppSettings settings = settingsRepository.getSettingsSnapshot();
        List<SubsystemHealth> list = new ArrayList<>();

        // 1. AI Providers & Models
        int modelCount = modelRepository.getAllModels().size();
        list.add(new SubsystemHealth(
                SubsystemId.PROVIDERS_MODELS,
                "AI Providers & Models",
                modelCount > 0 ? IntegrationStatus.ACTIVE : IntegrationStatus.UNAVAILABLE,
                modelCount + " catalog models • Active: " + modelRepository.getSelectedModelSnapshot().getName()
        ));

        // 2. Tool System
        List<ToolDefinition> allTools = toolRegistry.listDefinitions();
        int enabledTools = 0;
        for (ToolDefinition def : allTools) {
            if (settings.isToolEnabled(def.getName())) enabledTools++;
        }
        list.add(new SubsystemHealth(
                SubsystemId.TOOL_SYSTEM,
                "Tool System",
                enabledTools > 0 ? IntegrationStatus.ACTIVE : IntegrationStatus.UNAVAILABLE,
                enabledTools + "/" + allTools.size() + " tools enabled • Timeout " + settings.getToolTimeoutSeconds() + "s"
        ));

        // 3. Termux
        TermuxIntegrationManager.TermuxState tState = termuxManager.inspectState();
        list.add(new SubsystemHealth(
                SubsystemId.TERMUX,
                "Termux",
                tState.status,
                tState.detail
        ));

        // 4. Shizuku
        ShizukuIntegrationManager.ShizukuState sState = shizukuManager.inspectState();
        list.add(new SubsystemHealth(
                SubsystemId.SHIZUKU,
                "Shizuku",
                sState.status,
                sState.detail
        ));

        // 5. ADB Tools (strictly depends on verified Shizuku Binder + Permission + UserService)
        IntegrationStatus adbStatus;
        String adbSummary;
        if (sState.status == IntegrationStatus.ACTIVE) {
            adbStatus = IntegrationStatus.ACTIVE;
            adbSummary = "9 ADB toolsets ready • " + sState.privilegeLevel.getLabel();
        } else if (sState.status == IntegrationStatus.CONNECTED) {
            adbStatus = IntegrationStatus.CONNECTED;
            adbSummary = "Shizuku ready; bind UserService to activate ADB tools";
        } else {
            adbStatus = sState.status;
            adbSummary = "Requires active Shizuku connection (" + sState.detail + ")";
        }
        list.add(new SubsystemHealth(SubsystemId.ADB_TOOLS, "ADB Tools", adbStatus, adbSummary));

        // 6. Internet Access
        InternetAccessManager.NetworkHealth net = internetManager.inspectSystemNetwork();
        list.add(new SubsystemHealth(
                SubsystemId.INTERNET_ACCESS,
                "Internet Access",
                net.status,
                net.detail + " • Search: " + settings.getPreferredSearchEngine().toUpperCase()
        ));

        // 7. Download Management
        int dlCount = downloadManager.getDownloadsSnapshot().size();
        list.add(new SubsystemHealth(
                SubsystemId.DOWNLOAD_MANAGEMENT,
                "Download Management",
                downloadManager.getStatus(),
                dlCount + " record(s) • Dir: " + downloadManager.getDefaultDownloadDir().getName()
        ));

        // 8. File Access
        list.add(new SubsystemHealth(
                SubsystemId.FILE_ACCESS,
                "File Access",
                fileManager.getStatus(),
                fileManager.getStatusSummary()
        ));

        // 9. Agent Settings
        list.add(new SubsystemHealth(
                SubsystemId.AGENT_SETTINGS,
                "Agent Settings",
                IntegrationStatus.ACTIVE,
                "Default: " + settings.getDefaultAgentMode().getTitle() + " • Max " + settings.getAgentMaxSteps() + " steps"
        ));

        // 10. Permission Management
        boolean camOk = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        int safCount = fileManager.refreshGrantedFolders().size();
        String permSummary = "Camera: " + (camOk ? "Granted" : "Denied")
                + " • Termux: " + (tState.permissionGranted ? "Granted" : "No")
                + " • Shizuku: " + (sState.permissionGranted ? "Granted" : "No")
                + " • SAF Folders: " + safCount;
        list.add(new SubsystemHealth(
                SubsystemId.PERMISSION_MANAGEMENT,
                "Permission Management",
                IntegrationStatus.ACTIVE,
                permSummary
        ));

        return list;
    }
}
