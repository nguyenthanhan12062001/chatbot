package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY updated_at DESC")
    LiveData<List<AgentTaskEntity>> observeAll();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(AgentTaskEntity entity);

    @Query("DELETE FROM agent_tasks WHERE id = :taskId")
    void deleteById(String taskId);
}
