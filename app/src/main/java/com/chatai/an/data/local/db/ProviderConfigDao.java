package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ProviderConfigDao {
    @Query("SELECT * FROM provider_configs")
    LiveData<List<ProviderConfigEntity>> observeAll();

    @Query("SELECT * FROM provider_configs")
    List<ProviderConfigEntity> getAllSync();

    @Query("SELECT * FROM provider_configs WHERE provider_id = :providerId LIMIT 1")
    ProviderConfigEntity getByIdSync(String providerId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ProviderConfigEntity entity);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertAllIgnore(List<ProviderConfigEntity> entities);
}
