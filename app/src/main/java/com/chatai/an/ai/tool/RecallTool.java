package com.chatai.an.ai.tool;

import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.ToolDefinition;
import com.chatai.an.domain.model.ToolPermission;
import com.chatai.an.domain.model.ToolResult;
import com.chatai.an.domain.repository.MemoryRepository;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI tool for searching and retrieving specific saved memories without dumping the entire store.
 * Enforces scope/project isolation and records real usage statistics for returned memories.
 */
public class RecallTool implements AiTool {

    public static final String TOOL_NAME = "recall";

    private final MemoryRepository memoryRepository;

    public RecallTool(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                TOOL_NAME,
                "Search saved AI memories by query, category, scope, area, projectId, or memoryId.",
                "{\"query\":\"keywords\",\"memoryId\":\"optional_id\",\"category\":\"optional\",\"scope\":\"GLOBAL|PROJECT\",\"area\":\"CHAT|CODE|SHARED\",\"projectId\":\"optional\",\"includeArchived\":false,\"limit\":8}",
                ToolPermission.AUTO_ALLOW
        );
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String memoryId = getString(args, "memoryId", "").trim();
        if (!memoryId.isEmpty()) {
            MemoryItem item = memoryRepository.getByIdSync(memoryId);
            if (item == null) {
                return new ToolResult(TOOL_NAME, false, "", "No memory found for id: " + memoryId);
            }
            memoryRepository.recordUsageSync(java.util.Collections.singletonList(item.getId()));
            return new ToolResult(TOOL_NAME, true, formatItem(item), "");
        }

        String query = getString(args, "query", "").trim();
        String categoryStr = getString(args, "category", "").trim();
        String scopeStr = getString(args, "scope", "").trim();
        String areaStr = getString(args, "area", "").trim();
        String activeProject = getString(args, "_activeProjectId", "").trim();
        String projectId = getString(args, "projectId", activeProject).trim();
        boolean includeArchived = args.has("includeArchived") && args.get("includeArchived").isJsonPrimitive()
                && args.get("includeArchived").getAsBoolean();
        int limit = args.has("limit") && args.get("limit").isJsonPrimitive()
                ? Math.max(1, Math.min(20, args.get("limit").getAsInt()))
                : 8;

        MemoryCategory category = categoryStr.isEmpty() ? null : MemoryCategory.fromStored(categoryStr);
        MemoryScope scope = scopeStr.isEmpty() ? null : MemoryScope.fromStored(scopeStr);
        MemoryArea area = areaStr.isEmpty() ? null : MemoryArea.fromStored(areaStr);

        List<MemoryItem> matches = memoryRepository.searchMemoriesSync(
                query,
                category,
                scope,
                area,
                projectId,
                includeArchived
        );

        // Enforce strict project isolation when an active projectId is present and caller didn't specify scope=PROJECT for that same project
        List<MemoryItem> isolated = new ArrayList<>();
        List<String> usedIds = new ArrayList<>();
        for (MemoryItem item : matches) {
            if (!item.isEnabled() && !includeArchived) continue;
            if (item.getScope() == MemoryScope.PROJECT) {
                if (projectId.isEmpty() || !projectId.equals(item.getProjectId())) {
                    continue;
                }
            }
            isolated.add(item);
            usedIds.add(item.getId());
            if (isolated.size() >= limit) break;
        }

        if (isolated.isEmpty()) {
            return new ToolResult(TOOL_NAME, true, "No matching memories found.", "");
        }

        memoryRepository.recordUsageSync(usedIds);
        StringBuilder sb = new StringBuilder();
        for (MemoryItem item : isolated) {
            sb.append(formatItem(item)).append("\n");
        }
        return new ToolResult(TOOL_NAME, true, sb.toString().trim(), "");
    }

    private static String formatItem(MemoryItem item) {
        return "- [id=" + item.getId()
                + ", cat=" + item.getCategory().name()
                + ", scope=" + item.getScope().name()
                + (item.getScope() == MemoryScope.PROJECT ? ":" + item.getProjectId() : "")
                + ", area=" + item.getArea().name()
                + ", imp=" + item.getImportance()
                + (item.isPinned() ? ", PINNED" : "")
                + "] " + item.getContent();
    }

    private static String getString(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return args.get(key).getAsString();
    }
}
