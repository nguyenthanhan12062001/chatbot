package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;

import java.util.List;

public interface MemoryRepository {
    LiveData<List<MemoryItem>> observeAllMemories();

    List<MemoryItem> getActiveMemoriesForConversationSync(String conversationId);

    List<MemoryItem> getActiveCandidatesSync(String projectId, String conversationId);

    List<MemoryItem> getAllNonArchivedSync();

    MemoryItem getByIdSync(String id);

    List<MemoryItem> searchMemoriesSync(
            String query,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            boolean includeArchived
    );

    void addMemory(MemoryCategory category, String conversationId, String content);

    MemoryItem addMemorySync(
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String conversationId,
            String content,
            int importance,
            boolean pinned
    );

    void updateMemory(String id, MemoryCategory category, String content, boolean enabled);

    void updateMemoryFull(
            String id,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String content,
            int importance,
            boolean enabled,
            boolean pinned
    );

    void setMemoryEnabled(String id, boolean enabled);

    void setMemoryPinned(String id, boolean pinned);

    void recordUsageSync(List<String> memoryIds);

    MemoryItem mergeMemoriesSync(
            List<String> sourceIds,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String mergedContent,
            int importance,
            boolean pinned
    );

    void restoreMergedMemorySync(String mergedMemoryId);

    void deleteMemory(String id);
}
