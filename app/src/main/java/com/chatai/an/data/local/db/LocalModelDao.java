package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY imported_at DESC")
    LiveData<List<LocalModelEntity>> observeAll();

    @Query("SELECT * FROM local_models ORDER BY imported_at DESC")
    List<LocalModelEntity> getAllSync();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(LocalModelEntity entity);

    @Query("DELETE FROM local_models WHERE id = :id")
    void deleteById(String id);
}
