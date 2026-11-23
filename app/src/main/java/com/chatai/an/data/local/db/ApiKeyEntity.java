package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "api_keys",
        indices = {@Index("provider_id")}
)
public class ApiKeyEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    @ColumnInfo(name = "provider_id")
    public String providerId;

    @NonNull
    public String label;

    @NonNull
    @ColumnInfo(name = "encrypted_key")
    public String encryptedKey;

    @NonNull
    @ColumnInfo(name = "masked_preview")
    public String maskedPreview;

    public boolean enabled;

    public boolean active;

    @NonNull
    @ColumnInfo(name = "last_error")
    public String lastError;

    @ColumnInfo(name = "cooldown_until")
    public long cooldownUntil;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    public ApiKeyEntity(
            @NonNull String id,
            @NonNull String providerId,
            @NonNull String label,
            @NonNull String encryptedKey,
            @NonNull String maskedPreview,
            boolean enabled,
            boolean active,
            @NonNull String lastError,
            long cooldownUntil,
            long createdAt
    ) {
        this.id = id;
        this.providerId = providerId;
        this.label = label;
        this.encryptedKey = encryptedKey;
        this.maskedPreview = maskedPreview;
        this.enabled = enabled;
        this.active = active;
        this.lastError = lastError;
        this.cooldownUntil = cooldownUntil;
        this.createdAt = createdAt;
    }
}
