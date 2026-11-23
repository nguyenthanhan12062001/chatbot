package com.chatai.an.ai.memory;

import com.chatai.an.ai.tool.RememberTool;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.repository.MemoryRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identifies related non-archived memories within the same scope/project/category,
 * creates consolidated preview text for user confirmation, and applies non-destructive
 * merge (preserving originals with archived=true + mergedIntoId) and restore.
 */
public class MemoryMergeService {

    public static class MergeProposal {
        private final MemoryCategory category;
        private final MemoryScope scope;
        private final MemoryArea area;
        private final String projectId;
        private final List<MemoryItem> sources;
        private final String proposedContent;
        private final int maxImportance;
        private final boolean anyPinned;

        public MergeProposal(
                MemoryCategory category,
                MemoryScope scope,
                MemoryArea area,
                String projectId,
                List<MemoryItem> sources,
                String proposedContent,
                int maxImportance,
                boolean anyPinned
        ) {
            this.category = category;
            this.scope = scope;
            this.area = area;
            this.projectId = projectId != null ? projectId : "";
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
            this.proposedContent = proposedContent;
            this.maxImportance = maxImportance;
            this.anyPinned = anyPinned;
        }

        public MemoryCategory getCategory() {
            return category;
        }

        public MemoryScope getScope() {
            return scope;
        }

        public MemoryArea getArea() {
            return area;
        }

        public String getProjectId() {
            return projectId;
        }

        public List<MemoryItem> getSources() {
            return sources;
        }

        public String getProposedContent() {
            return proposedContent;
        }

        public int getMaxImportance() {
            return maxImportance;
        }

        public boolean isAnyPinned() {
            return anyPinned;
        }
    }

    private final MemoryRepository memoryRepository;

    public MemoryMergeService(MemoryRepository memoryRepository) {
        this.memoryRepository = memoryRepository;
    }

    public List<MergeProposal> findMergeProposals() {
        List<MemoryItem> active = memoryRepository.getAllNonArchivedSync();
        return findProposalsFromList(active);
    }

    public static List<MergeProposal> findProposalsFromList(List<MemoryItem> items) {
        if (items == null || items.size() < 2) {
            return Collections.emptyList();
        }
        // Group strictly by scope + projectId + category so we never merge across projects or categories
        Map<String, List<MemoryItem>> buckets = new HashMap<>();
        for (MemoryItem item : items) {
            if (item == null || item.isArchived()) continue;
            String key = item.getScope().name() + "|" + item.getProjectId() + "|" + item.getCategory().name();
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        List<MergeProposal> proposals = new ArrayList<>();
        for (List<MemoryItem> bucket : buckets.values()) {
            if (bucket.size() < 2) continue;
            boolean[] visited = new boolean[bucket.size()];
            for (int i = 0; i < bucket.size(); i++) {
                if (visited[i]) continue;
                MemoryItem seed = bucket.get(i);
                Set<String> seedTokens = RememberTool.tokenize(RememberTool.normalize(seed.getContent()));
                List<MemoryItem> cluster = new ArrayList<>();
                cluster.add(seed);
                visited[i] = true;

                for (int j = i + 1; j < bucket.size(); j++) {
                    if (visited[j]) continue;
                    MemoryItem other = bucket.get(j);
                    Set<String> otherTokens = RememberTool.tokenize(RememberTool.normalize(other.getContent()));
                    if (areRelated(seedTokens, otherTokens)) {
                        cluster.add(other);
                        visited[j] = true;
                        seedTokens = new HashSet<>(seedTokens);
                        seedTokens.addAll(otherTokens);
                    }
                }

                if (cluster.size() >= 2) {
                    proposals.add(buildProposal(cluster));
                }
            }
        }
        return proposals;
    }

    private static boolean areRelated(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        int shared = 0;
        for (String t : a) {
            if (b.contains(t)) shared++;
        }
        double sim = RememberTool.jaccardSimilarity(a, b);
        return shared >= 2 || sim >= 0.28;
    }

    private static MergeProposal buildProposal(List<MemoryItem> cluster) {
        MemoryItem first = cluster.get(0);
        int maxImp = first.getImportance();
        boolean pinned = first.isPinned();
        MemoryArea chosenArea = first.getArea();

        LinkedHashSet<String> clauses = new LinkedHashSet<>();
        for (MemoryItem item : cluster) {
            if (item.getImportance() > maxImp) maxImp = item.getImportance();
            if (item.isPinned()) pinned = true;
            if (item.getArea() != first.getArea()) chosenArea = MemoryArea.SHARED;
            String trimmed = item.getContent().trim();
            if (!trimmed.isEmpty()) {
                clauses.add(trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
            }
        }
        String merged = String.join("; ", clauses) + ".";
        return new MergeProposal(
                first.getCategory(),
                first.getScope(),
                chosenArea,
                first.getProjectId(),
                cluster,
                merged,
                maxImp,
                pinned
        );
    }

    public MemoryItem applyMerge(MergeProposal proposal, String editedContent) {
        List<String> sourceIds = new ArrayList<>();
        for (MemoryItem m : proposal.getSources()) {
            sourceIds.add(m.getId());
        }
        String finalContent = (editedContent != null && !editedContent.trim().isEmpty())
                ? editedContent.trim()
                : proposal.getProposedContent();
        return memoryRepository.mergeMemoriesSync(
                sourceIds,
                proposal.getCategory(),
                proposal.getScope(),
                proposal.getArea(),
                proposal.getProjectId(),
                finalContent,
                proposal.getMaxImportance(),
                proposal.isAnyPinned()
        );
    }

    public void restoreMerged(String mergedMemoryId) {
        memoryRepository.restoreMergedMemorySync(mergedMemoryId);
    }
}
