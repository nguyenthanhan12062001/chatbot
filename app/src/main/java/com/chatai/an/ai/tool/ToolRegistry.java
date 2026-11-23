package com.chatai.an.ai.tool;

import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AppSettings;
import com.chatai.an.domain.model.ToolCategory;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolResult;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {
    private final Map<String, AiTool> tools = new LinkedHashMap<>();

    public void register(AiTool tool) {
        if (tool == null || tool.getDefinition() == null) return;
        tools.put(tool.getDefinition().getName(), tool);
    }

    public AiTool getTool(String name) {
        return name != null ? tools.get(name.trim()) : null;
    }

    public List<ToolDefinition> listDefinitions() {
        List<ToolDefinition> defs = new ArrayList<>();
        for (AiTool tool : tools.values()) {
            defs.add(tool.getDefinition());
        }
        return Collections.unmodifiableList(defs);
    }

    public List<ToolDefinition> listEnabledDefinitions(AppSettings settings) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (AiTool tool : tools.values()) {
            ToolDefinition def = tool.getDefinition();
            if (settings == null || settings.isToolEnabled(def.getName())) {
                defs.add(def);
            }
        }
        return Collections.unmodifiableList(defs);
    }

    public List<ToolDefinition> listDefinitionsForMode(AgentMode mode, AppSettings settings) {
        List<ToolDefinition> defs = new ArrayList<>();
        for (AiTool tool : tools.values()) {
            ToolDefinition def = tool.getDefinition();
            if (settings != null && !settings.isToolEnabled(def.getName())) {
                continue;
            }
            if (mode == AgentMode.CHAT && !isAllowedInChatMode(def)) {
                continue;
            }
            defs.add(def);
        }
        return Collections.unmodifiableList(defs);
    }

    private static boolean isAllowedInChatMode(ToolDefinition def) {
        String name = def.getName();
        if (def.getCategory() == ToolCategory.TERMINAL || def.getCategory() == ToolCategory.TERMUX) {
            return false;
        }
        if ("fs_write_file".equals(name) || "fs_edit_file".equals(name) || "fs_delete".equals(name)) {
            return false;
        }
        if ("adb_shell".equals(name)
                || "adb_package_manager".equals(name)
                || "adb_activity_manager".equals(name)
                || "adb_permission_manager".equals(name)
                || "adb_process_manager".equals(name)
                || "adb_system_settings".equals(name)) {
            return false;
        }
        return true;
    }

    public ToolResult executeTool(String name, JsonObject args) {
        AiTool tool = getTool(name);
        if (tool == null) {
            return new ToolResult(name != null ? name : "", false, "", "Unknown tool: " + name);
        }
        return tool.execute(args != null ? args : new JsonObject());
    }

    public String formatCatalogPrompt() {
        return formatCatalogPrompt(listDefinitions());
    }

    public String formatCatalogPrompt(List<ToolDefinition> defs) {
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition def : defs) {
            sb.append("- ").append(def.getName())
                    .append(" [").append(def.getCategory().getLabel()).append("]")
                    .append(" (permission=").append(def.getPermission().name()).append("): ")
                    .append(def.getDescription())
                    .append(" | schema: ").append(def.getInputSchemaJson())
                    .append("\n");
        }
        return sb.toString();
    }

    public static String formatCatalogSummary(List<ToolDefinition> defs) {
        if (defs == null || defs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition def : defs) {
            sb.append("- ").append(def.getName())
                    .append(" (").append(def.getCategory().getLabel())
                    .append(", ").append(def.getPermission().name()).append("): ")
                    .append(def.getDescription())
                    .append(" | schema: ").append(def.getInputSchemaJson())
                    .append("\n");
        }
        return sb.toString();
    }

    public List<ToolDefinition> listByCategory(ToolCategory category) {
        List<ToolDefinition> out = new ArrayList<>();
        for (AiTool t : tools.values()) {
            if (t.getDefinition().getCategory() == category) {
                out.add(t.getDefinition());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
