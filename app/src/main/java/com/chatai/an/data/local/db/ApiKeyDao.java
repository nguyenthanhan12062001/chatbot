package com.chatai.an.data.local.db;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ApiKeyDao {
    @Query("SELECT * FROM api_keys WHERE provider_id = :providerId ORDER BY created_at ASC")
    LiveData<List<ApiKeyEntity>> observeByProvider(String providerId);

    @Query("SELECT * FROM api_keys WHERE provider_id = :providerId ORDER BY created_at ASC")
    List<ApiKeyEntity> getByProviderSync(String providerId);

    @Query("SELECT * FROM api_keys WHERE id = :keyId LIMIT 1")
    ApiKeyEntity getByIdSync(String keyId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ApiKeyEntity entity);

    @Query("UPDATE api_keys SET enabled = :enabled WHERE id = :keyId")
    void setEnabled(String keyId, boolean enabled);

    @Query("UPDATE api_keys SET active = CASE WHEN id = :keyId THEN 1 ELSE 0 END WHERE provider_id = :providerId")
    void setActiveForProvider(String providerId, String keyId);

    @Query("UPDATE api_keys SET last_error = :lastError, cooldown_until = :cooldownUntil WHERE id = :keyId")
    void updateFailureStatus(String keyId, String lastError, long cooldownUntil);

    @Query("DELETE FROM api_keys WHERE id = :keyId")
    void deleteById(String keyId);
}
