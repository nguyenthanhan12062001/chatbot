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

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI tool for storing, updating, or deleting durable user & project memories.
 * Enforces quality filtering (rejects conversational filler) and duplicate detection.
 */
public class RememberTool implements AiTool {

    public static final String TOOL_NAME = "remember";
    private static final Pattern FILLER_PATTERN = Pattern.compile(
            "^(hi|hello|hey|ok|okay|thanks|thank you|sure|got it|yes|no|chào|cảm ơn|được rồi|vâng|ok bạn)[.!\\s]*$",
            Pattern.CASE_INSENSITIVE
    );

    private final MemoryRepository memoryRepository;

    public RememberTool(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    @Override
    public ToolDefinition getDefinition() {
        return new ToolDefinition(
                TOOL_NAME,
                "Save, update, or delete a durable user preference, profile fact, project context, or decision.",
                "{\"operation\":\"save|update|delete\",\"memoryId\":\"optional_id\",\"content\":\"fact to remember\",\"category\":\"PROFILE|PREFERENCE|PROJECT|DECISION|FACT\",\"scope\":\"GLOBAL|PROJECT\",\"area\":\"CHAT|CODE|SHARED\",\"projectId\":\"optional_project\",\"importance\":1..5,\"pinned\":false}",
                ToolPermission.AUTO_ALLOW
        );
    }

    @Override
    public ToolResult execute(JsonObject args) {
        String op = getString(args, "operation", "save").toLowerCase(Locale.ROOT);
        if ("delete".equals(op)) {
            String memoryId = getString(args, "memoryId", "");
            if (memoryId.isEmpty()) {
                return new ToolResult(TOOL_NAME, false, "", "memoryId is required for delete.");
            }
            MemoryItem existing = memoryRepository.getByIdSync(memoryId);
            if (existing == null) {
                return new ToolResult(TOOL_NAME, false, "", "Memory not found: " + memoryId);
            }
            memoryRepository.deleteMemory(memoryId);
            return new ToolResult(TOOL_NAME, true, "Deleted memory " + memoryId, "");
        }

        String content = getString(args, "content", "").trim();
        if (isTrivialFiller(content)) {
            return new ToolResult(TOOL_NAME, false, "", "Skipped saving trivial or empty memory.");
        }

        MemoryCategory category = MemoryCategory.fromStored(getString(args, "category", "FACT"));
        MemoryScope scope = MemoryScope.fromStored(getString(args, "scope", "GLOBAL"));
        MemoryArea area = MemoryArea.fromStored(getString(args, "area", "SHARED"));
        String projectId = getString(args, "projectId", "").trim();
        if (scope == MemoryScope.PROJECT && projectId.isEmpty()) {
            projectId = getString(args, "_activeProjectId", "").trim();
            if (projectId.isEmpty()) {
                scope = MemoryScope.GLOBAL;
            }
        }
        String conversationId = getString(args, "conversationId", getString(args, "_activeConversationId", ""));
        int importance = args.has("importance") && args.get("importance").isJsonPrimitive()
                ? Math.max(1, Math.min(5, args.get("importance").getAsInt()))
                : 3;
        boolean pinned = args.has("pinned") && args.get("pinned").isJsonPrimitive() && args.get("pinned").getAsBoolean();

        if ("update".equals(op)) {
            String memoryId = getString(args, "memoryId", "");
            MemoryItem existing = memoryRepository.getByIdSync(memoryId);
            if (existing == null) {
                return new ToolResult(TOOL_NAME, false, "", "Cannot update; memory not found: " + memoryId);
            }
            memoryRepository.updateMemoryFull(
                    memoryId,
                    args.has("category") ? category : existing.getCategory(),
                    args.has("scope") ? scope : existing.getScope(),
                    args.has("area") ? area : existing.getArea(),
                    args.has("projectId") ? projectId : existing.getProjectId(),
                    content,
                    args.has("importance") ? importance : existing.getImportance(),
                    existing.isEnabled(),
                    args.has("pinned") ? pinned : existing.isPinned()
            );
            return new ToolResult(TOOL_NAME, true, "Updated memory " + memoryId + ": " + content, "");
        }

        // Duplicate detection before creating
        MemoryItem duplicate = findDuplicate(content, scope, projectId);
        if (duplicate != null) {
            int mergedImportance = Math.max(duplicate.getImportance(), importance);
            boolean mergedPin = duplicate.isPinned() || pinned;
            String updatedText = content.length() > duplicate.getContent().length() ? content : duplicate.getContent();
            memoryRepository.updateMemoryFull(
                    duplicate.getId(),
                    duplicate.getCategory(),
                    duplicate.getScope(),
                    duplicate.getArea(),
                    duplicate.getProjectId(),
                    updatedText,
                    mergedImportance,
                    true,
                    mergedPin
            );
            return new ToolResult(
                    TOOL_NAME,
                    true,
                    "Duplicate detected; refreshed existing memory (" + duplicate.getId() + "): " + updatedText,
                    ""
            );
        }

        MemoryItem created = memoryRepository.addMemorySync(
                category,
                scope,
                area,
                scope == MemoryScope.PROJECT ? projectId : "",
                conversationId,
                content,
                importance,
                pinned
        );
        return new ToolResult(
                TOOL_NAME,
                true,
                "Saved memory (" + created.getId() + ", " + category.name() + "/" + scope.name() + "): " + created.getContent(),
                ""
        );
    }

    public static boolean isTrivialFiller(String content) {
        if (content == null) return true;
        String trimmed = content.trim();
        if (trimmed.length() < 8) return true;
        return FILLER_PATTERN.matcher(trimmed).matches();
    }

    private MemoryItem findDuplicate(String candidateText, MemoryScope scope, String projectId) {
        List<MemoryItem> existing = memoryRepository.getAllNonArchivedSync();
        if (existing == null || existing.isEmpty()) return null;
        String normCandidate = normalize(candidateText);
        Set<String> candidateTokens = tokenize(normCandidate);

        for (MemoryItem item : existing) {
            if (item.getScope() != scope) continue;
            if (scope == MemoryScope.PROJECT && !projectId.equals(item.getProjectId())) continue;
            String normItem = normalize(item.getContent());
            if (normItem.equals(normCandidate)) {
                return item;
            }
            if (normItem.length() >= 12 && normCandidate.length() >= 12
                    && (normItem.contains(normCandidate) || normCandidate.contains(normItem))) {
                return item;
            }
            if (jaccardSimilarity(candidateTokens, tokenize(normItem)) >= 0.75) {
                return item;
            }
        }
        return null;
    }

    public static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static Set<String> tokenize(String normalized) {
        Set<String> tokens = new HashSet<>();
        if (normalized == null || normalized.isEmpty()) return tokens;
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    public static double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersection = 0;
        for (String t : a) {
            if (b.contains(t)) intersection++;
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static String getString(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return args.get(key).getAsString();
    }
}
