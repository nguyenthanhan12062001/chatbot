package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY archived ASC, pinned DESC, importance DESC, updated_at DESC")
    LiveData<List<MemoryEntity>> observeAll();

    @Query("SELECT * FROM memories WHERE archived = 0 ORDER BY pinned DESC, importance DESC, updated_at DESC")
    List<MemoryEntity> getAllNonArchivedSync();

    @Query("SELECT * FROM memories WHERE enabled = 1 AND archived = 0 " +
            "AND (scope = 'GLOBAL' OR (scope = 'PROJECT' AND project_id = :projectId AND :projectId != '') " +
            "OR (conversation_id IS NOT NULL AND conversation_id = :conversationId AND :conversationId != '')) " +
            "ORDER BY pinned DESC, importance DESC, updated_at DESC")
    List<MemoryEntity> getActiveCandidatesSync(String projectId, String conversationId);

    @Query("SELECT * FROM memories WHERE enabled = 1 AND archived = 0 AND (conversation_id IS NULL OR conversation_id = '' OR conversation_id = :conversationId) ORDER BY pinned DESC, importance DESC, updated_at DESC")
    List<MemoryEntity> getActiveForConversationSync(String conversationId);

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    MemoryEntity getByIdSync(String id);

    @Query("SELECT * FROM memories WHERE merged_into_id = :mergedId")
    List<MemoryEntity> getMergedOriginalsSync(String mergedId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(MemoryEntity entity);

    @Query("UPDATE memories SET category = :category, content = :content, enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    void update(String id, String category, String content, boolean enabled, long updatedAt);

    @Query("UPDATE memories SET category = :category, scope = :scope, area = :area, project_id = :projectId, content = :content, importance = :importance, enabled = :enabled, pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    void updateFull(String id, String category, String scope, String area, String projectId, String content, int importance, boolean enabled, boolean pinned, long updatedAt);

    @Query("UPDATE memories SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    void setEnabled(String id, boolean enabled, long updatedAt);

    @Query("UPDATE memories SET pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    void setPinned(String id, boolean pinned, long updatedAt);

    @Query("UPDATE memories SET last_used_at = :usedAt, usage_count = usage_count + 1 WHERE id = :id")
    void recordUsage(String id, long usedAt);

    @Query("UPDATE memories SET archived = 1, enabled = 0, merged_into_id = :mergedIntoId, updated_at = :updatedAt WHERE id = :id")
    void markArchivedMerged(String id, String mergedIntoId, long updatedAt);

    @Query("UPDATE memories SET archived = 0, enabled = 1, merged_into_id = NULL, updated_at = :updatedAt WHERE merged_into_id = :mergedId")
    void restoreMergedOriginals(String mergedId, long updatedAt);

    @Query("DELETE FROM memories WHERE id = :id")
    void deleteById(String id);
}
