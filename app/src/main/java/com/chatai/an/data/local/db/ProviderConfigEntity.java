package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "provider_configs")
public class ProviderConfigEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "provider_id")
    public String providerId;

    @NonNull
    @ColumnInfo(name = "display_name")
    public String displayName;

    @NonNull
    @ColumnInfo(name = "provider_type")
    public String providerType;

    @NonNull
    @ColumnInfo(name = "protocol_type")
    public String protocolType;

    @NonNull
    @ColumnInfo(name = "base_url")
    public String baseUrl;

    public boolean enabled;

    @ColumnInfo(name = "key_rotation_enabled")
    public boolean keyRotationEnabled;

    @NonNull
    @ColumnInfo(name = "fallback_model_id")
    public String fallbackModelId;

    @NonNull
    @ColumnInfo(name = "custom_headers_json")
    public String customHeadersJson;

    @NonNull
    @ColumnInfo(name = "last_connection_status")
    public String lastConnectionStatus;

    @ColumnInfo(name = "last_tested_at")
    public long lastTestedAt;

    public ProviderConfigEntity(
            @NonNull String providerId,
            @NonNull String displayName,
            @NonNull String providerType,
            @NonNull String protocolType,
            @NonNull String baseUrl,
            boolean enabled,
            boolean keyRotationEnabled,
            @NonNull String fallbackModelId,
            @NonNull String customHeadersJson,
            @NonNull String lastConnectionStatus,
            long lastTestedAt
    ) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.providerType = providerType;
        this.protocolType = protocolType;
        this.baseUrl = baseUrl;
        this.enabled = enabled;
        this.keyRotationEnabled = keyRotationEnabled;
        this.fallbackModelId = fallbackModelId;
        this.customHeadersJson = customHeadersJson;
        this.lastConnectionStatus = lastConnectionStatus;
        this.lastTestedAt = lastTestedAt;
    }
}
