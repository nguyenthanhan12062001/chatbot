package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.ChatDatabase;
import com.chatai.an.data.local.db.EntityMapper;
import com.chatai.an.data.local.db.MemoryDao;
import com.chatai.an.data.local.db.MemoryEntity;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.repository.MemoryRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LocalMemoryRepository implements MemoryRepository {
    private final ChatDatabase database;
    private final MemoryDao memoryDao;
    private final AppExecutors executors;

    public LocalMemoryRepository(ChatDatabase database, AppExecutors executors) {
        this.database = database;
        this.memoryDao = database.memoryDao();
        this.executors = executors;
    }

    @Override
    public LiveData<List<MemoryItem>> observeAllMemories() {
        return Transformations.map(memoryDao.observeAll(), EntityMapper::toMemoryList);
    }

    @Override
    public List<MemoryItem> getActiveMemoriesForConversationSync(String conversationId) {
        return EntityMapper.toMemoryList(
                memoryDao.getActiveForConversationSync(conversationId != null ? conversationId : "")
        );
    }

    @Override
    public List<MemoryItem> getActiveCandidatesSync(String projectId, String conversationId) {
        return EntityMapper.toMemoryList(
                memoryDao.getActiveCandidatesSync(
                        projectId != null ? projectId.trim() : "",
                        conversationId != null ? conversationId.trim() : ""
                )
        );
    }

    @Override
    public List<MemoryItem> getAllNonArchivedSync() {
        return EntityMapper.toMemoryList(memoryDao.getAllNonArchivedSync());
    }

    @Override
    public MemoryItem getByIdSync(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        MemoryEntity e = memoryDao.getByIdSync(id.trim());
        return e != null ? EntityMapper.toDomain(e) : null;
    }

    @Override
    public List<MemoryItem> searchMemoriesSync(
            String query,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            boolean includeArchived
    ) {
        List<MemoryEntity> base = memoryDao.getAllNonArchivedSync();
        List<MemoryItem> items = EntityMapper.toMemoryList(base);
        String qLower = query != null ? query.trim().toLowerCase() : "";
        String projLower = projectId != null ? projectId.trim().toLowerCase() : "";
        List<MemoryItem> matched = new ArrayList<>();

        for (MemoryItem item : items) {
            if (!includeArchived && item.isArchived()) continue;
            if (category != null && item.getCategory() != category) continue;
            if (scope != null && item.getScope() != scope) continue;
            if (area != null && item.getArea() != area) continue;
            if (!projLower.isEmpty() && item.getScope() == MemoryScope.PROJECT
                    && !item.getProjectId().toLowerCase().equals(projLower)) {
                continue;
            }
            if (!qLower.isEmpty()) {
                String hay = (item.getContent() + " " + item.getProjectId() + " " + item.getCategory().getCode()).toLowerCase();
                boolean allTerms = true;
                for (String term : qLower.split("\\s+")) {
                    if (!term.isEmpty() && !hay.contains(term)) {
                        allTerms = false;
                        break;
                    }
                }
                if (!allTerms) continue;
            }
            matched.add(item);
        }
        return matched;
    }

    @Override
    public void addMemory(MemoryCategory category, String conversationId, String content) {
        if (content == null || content.trim().isEmpty()) return;
        executors.diskIO().execute(() -> addMemorySync(
                category,
                MemoryScope.GLOBAL,
                MemoryArea.SHARED,
                "",
                conversationId,
                content,
                3,
                false
        ));
    }

    @Override
    public MemoryItem addMemorySync(
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String conversationId,
            String content,
            int importance,
            boolean pinned
    ) {
        if (content == null || content.trim().isEmpty()) return null;
        long now = System.currentTimeMillis();
        String safeProj = projectId != null ? projectId.trim() : "";
        MemoryScope effectiveScope = scope != null ? scope : (safeProj.isEmpty() ? MemoryScope.GLOBAL : MemoryScope.PROJECT);
        if (effectiveScope == MemoryScope.PROJECT && safeProj.isEmpty()) {
            effectiveScope = MemoryScope.GLOBAL;
        }
        MemoryEntity entity = new MemoryEntity(
                UUID.randomUUID().toString(),
                (category != null ? category : MemoryCategory.FACT).getCode(),
                effectiveScope.getCode(),
                (area != null ? area : MemoryArea.SHARED).getCode(),
                effectiveScope == MemoryScope.PROJECT ? safeProj : "",
                conversationId,
                content.trim(),
                Math.max(1, Math.min(5, importance)),
                true,
                pinned,
                false,
                null,
                now,
                now,
                0L,
                0
        );
        memoryDao.insert(entity);
        return EntityMapper.toDomain(entity);
    }

    @Override
    public void updateMemory(String id, MemoryCategory category, String content, boolean enabled) {
        if (id == null || content == null || content.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        String cat = (category != null ? category : MemoryCategory.FACT).getCode();
        executors.diskIO().execute(() -> memoryDao.update(id, cat, content.trim(), enabled, now));
    }

    @Override
    public void updateMemoryFull(
            String id,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String content,
            int importance,
            boolean enabled,
            boolean pinned
    ) {
        if (id == null || content == null || content.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        String safeProj = projectId != null ? projectId.trim() : "";
        MemoryScope effectiveScope = scope != null ? scope : MemoryScope.GLOBAL;
        if (effectiveScope == MemoryScope.PROJECT && safeProj.isEmpty()) {
            effectiveScope = MemoryScope.GLOBAL;
        }
        String finalScope = effectiveScope.getCode();
        String finalProj = effectiveScope == MemoryScope.PROJECT ? safeProj : "";
        String finalCat = (category != null ? category : MemoryCategory.FACT).getCode();
        String finalArea = (area != null ? area : MemoryArea.SHARED).getCode();
        int clampedImp = Math.max(1, Math.min(5, importance));
        executors.diskIO().execute(() ->
                memoryDao.updateFull(id, finalCat, finalScope, finalArea, finalProj, content.trim(), clampedImp, enabled, pinned, now)
        );
    }

    @Override
    public void setMemoryEnabled(String id, boolean enabled) {
        if (id == null) return;
        long now = System.currentTimeMillis();
        executors.diskIO().execute(() -> memoryDao.setEnabled(id, enabled, now));
    }

    @Override
    public void setMemoryPinned(String id, boolean pinned) {
        if (id == null) return;
        long now = System.currentTimeMillis();
        executors.diskIO().execute(() -> memoryDao.setPinned(id, pinned, now));
    }

    @Override
    public void recordUsageSync(List<String> memoryIds) {
        if (memoryIds == null || memoryIds.isEmpty()) return;
        long now = System.currentTimeMillis();
        database.runInTransaction(() -> {
            for (String id : memoryIds) {
                if (id != null && !id.isEmpty()) {
                    memoryDao.recordUsage(id, now);
                }
            }
        });
    }

    @Override
    public MemoryItem mergeMemoriesSync(
            List<String> sourceIds,
            MemoryCategory category,
            MemoryScope scope,
            MemoryArea area,
            String projectId,
            String mergedContent,
            int importance,
            boolean pinned
    ) {
        if (sourceIds == null || sourceIds.isEmpty() || mergedContent == null || mergedContent.trim().isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        String mergedId = UUID.randomUUID().toString();
        String safeProj = projectId != null ? projectId.trim() : "";
        MemoryScope effectiveScope = scope != null ? scope : (safeProj.isEmpty() ? MemoryScope.GLOBAL : MemoryScope.PROJECT);
        if (effectiveScope == MemoryScope.PROJECT && safeProj.isEmpty()) {
            effectiveScope = MemoryScope.GLOBAL;
        }
        MemoryEntity mergedEntity = new MemoryEntity(
                mergedId,
                (category != null ? category : MemoryCategory.FACT).getCode(),
                effectiveScope.getCode(),
                (area != null ? area : MemoryArea.SHARED).getCode(),
                effectiveScope == MemoryScope.PROJECT ? safeProj : "",
                null,
                mergedContent.trim(),
                Math.max(1, Math.min(5, importance)),
                true,
                pinned,
                false,
                null,
                now,
                now,
                0L,
                0
        );
        database.runInTransaction(() -> {
            memoryDao.insert(mergedEntity);
            for (String srcId : sourceIds) {
                if (srcId != null && !srcId.isEmpty() && !srcId.equals(mergedId)) {
                    memoryDao.markArchivedMerged(srcId, mergedId, now);
                }
            }
        });
        return EntityMapper.toDomain(mergedEntity);
    }

    @Override
    public void restoreMergedMemorySync(String mergedMemoryId) {
        if (mergedMemoryId == null || mergedMemoryId.isEmpty()) return;
        long now = System.currentTimeMillis();
        database.runInTransaction(() -> {
            List<MemoryEntity> originals = memoryDao.getMergedOriginalsSync(mergedMemoryId);
            if (originals != null && !originals.isEmpty()) {
                memoryDao.restoreMergedOriginals(mergedMemoryId, now);
                memoryDao.setEnabled(mergedMemoryId, false, now);
            } else {
                // If user taps restore on an archived individual item directly
                MemoryEntity single = memoryDao.getByIdSync(mergedMemoryId);
                if (single != null && single.archived) {
                    memoryDao.updateFull(
                            single.id, single.category, single.scope, single.area, single.projectId,
                            single.content, single.importance, true, single.pinned, now
                    );
                }
            }
        });
    }

    @Override
    public void deleteMemory(String id) {
        if (id == null) return;
        executors.diskIO().execute(() -> memoryDao.deleteById(id));
    }
}
