package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    LiveData<List<ConversationEntity>> observeAll();

    @Query("SELECT DISTINCT c.* FROM conversations c " +
            "LEFT JOIN messages m ON c.id = m.conversation_id " +
            "WHERE c.title LIKE '%' || :query || '%' " +
            "OR m.content LIKE '%' || :query || '%' " +
            "ORDER BY c.updated_at DESC")
    LiveData<List<ConversationEntity>> search(String query);

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    ConversationEntity getByIdSync(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ConversationEntity entity);

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :id")
    void updateTitle(String id, String title, long updatedAt);

    @Query("UPDATE conversations SET model_id = :modelId, updated_at = :updatedAt WHERE id = :id")
    void updateModel(String id, String modelId, long updatedAt);

    @Query("UPDATE conversations SET project_id = :projectId, updated_at = :updatedAt WHERE id = :id")
    void updateProjectId(String id, String projectId, long updatedAt);

    @Query("UPDATE conversations SET compaction_summary = :summary, compacted_until = :compactedUntil WHERE id = :id")
    void updateCompaction(String id, String summary, long compactedUntil);

    @Query("UPDATE conversations SET last_message_preview = :preview, updated_at = :updatedAt WHERE id = :id")
    void updatePreview(String id, String preview, long updatedAt);

    @Query("DELETE FROM conversations WHERE id = :id")
    void deleteById(String id);

    @Query("DELETE FROM conversations")
    void deleteAll();
}
