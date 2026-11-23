package com.chatai.an.ai.memory;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.tool.RememberTool;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.repository.MemoryRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Contextual Memory Retrieval Engine.
 * Selects only relevant, active, non-archived memories within a token budget,
 * enforcing GLOBAL vs PROJECT isolation and prioritizing pinned/high-importance/area matches.
 */
public class MemoryRetrievalEngine {

    private final MemoryRepository memoryRepository;

    public MemoryRetrievalEngine(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    public List<MemoryItem> selectContextualMemories(
            String queryText,
            String projectId,
            String conversationId,
            int maxTokenBudget,
            int maxItems
    ) {
        List<MemoryItem> candidates = memoryRepository.getActiveCandidatesSync(projectId, conversationId);
        return rankAndFilter(candidates, queryText, projectId, maxTokenBudget, maxItems, true);
    }

    public List<MemoryItem> rankAndFilter(
            List<MemoryItem> candidates,
            String queryText,
            String projectId,
            int maxTokenBudget,
            int maxItems,
            boolean recordUsage
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        String cleanProject = projectId != null ? projectId.trim() : "";
        String normQuery = RememberTool.normalize(queryText);
        Set<String> queryTokens = RememberTool.tokenize(normQuery);
        boolean codeContext = isCodePrompt(queryText);

        List<ScoredItem> scored = new ArrayList<>();
        for (MemoryItem item : candidates) {
            if (item == null || !item.isEnabled() || item.isArchived()) {
                continue;
            }
            // Enforce strict PROJECT isolation
            if (item.getScope() == MemoryScope.PROJECT) {
                if (cleanProject.isEmpty() || !cleanProject.equals(item.getProjectId())) {
                    continue;
                }
            }

            Set<String> memTokens = RememberTool.tokenize(RememberTool.normalize(item.getContent()));
            int overlapCount = 0;
            for (String qt : queryTokens) {
                if (memTokens.contains(qt)) {
                    overlapCount++;
                }
            }

            // If query has meaningful terms, exclude completely unrelated low-priority unpinned facts
            boolean hasSignal = item.isPinned()
                    || item.getImportance() >= 4
                    || (item.getScope() == MemoryScope.PROJECT && !cleanProject.isEmpty())
                    || overlapCount > 0;
            if (!queryTokens.isEmpty() && !hasSignal) {
                continue;
            }

            double score = 0.0;
            if (item.isPinned()) score += 50.0;
            score += item.getImportance() * 8.0;
            if (item.getScope() == MemoryScope.PROJECT && !cleanProject.isEmpty()) {
                score += 18.0;
            }
            if (codeContext && item.getArea() == MemoryArea.CODE) {
                score += 12.0;
            } else if (!codeContext && item.getArea() == MemoryArea.CHAT) {
                score += 10.0;
            } else if (item.getArea() == MemoryArea.SHARED) {
                score += 6.0;
            }

            if (!queryTokens.isEmpty() && overlapCount > 0) {
                double ratio = (double) overlapCount / Math.max(1, Math.min(queryTokens.size(), memTokens.size()));
                score += Math.min(45.0, overlapCount * 12.0 + ratio * 20.0);
            }
            score += Math.min(8.0, item.getUsageCount() * 0.5);

            scored.add(new ScoredItem(item, score));
        }

        Collections.sort(scored, (a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            if (cmp != 0) return cmp;
            return Long.compare(b.item.getUpdatedAt(), a.item.getUpdatedAt());
        });

        List<MemoryItem> selected = new ArrayList<>();
        List<String> selectedIds = new ArrayList<>();
        int usedTokens = 0;
        int effectiveBudget = Math.max(120, maxTokenBudget);
        int effectiveLimit = Math.max(1, maxItems);

        for (ScoredItem entry : scored) {
            int estTokens = ContextManager.estimateTokens(entry.item.getContent()) + 12;
            if (!selected.isEmpty() && (usedTokens + estTokens > effectiveBudget || selected.size() >= effectiveLimit)) {
                break;
            }
            selected.add(entry.item);
            selectedIds.add(entry.item.getId());
            usedTokens += estTokens;
        }

        if (recordUsage && !selectedIds.isEmpty() && memoryRepository != null) {
            memoryRepository.recordUsageSync(selectedIds);
        }
        return selected;
    }

    public static boolean isCodePrompt(String queryText) {
        if (queryText == null) return false;
        String lower = queryText.toLowerCase(Locale.ROOT);
        return lower.contains("```")
                || lower.contains("java")
                || lower.contains("kotlin")
                || lower.contains("python")
                || lower.contains("code")
                || lower.contains("bug")
                || lower.contains("error")
                || lower.contains("class ")
                || lower.contains("function")
                || lower.contains("sql")
                || lower.contains("gradle");
    }

    private static class ScoredItem {
        final MemoryItem item;
        final double score;

        ScoredItem(MemoryItem item, double score) {
            this.item = item;
            this.score = score;
        }
    }
}
