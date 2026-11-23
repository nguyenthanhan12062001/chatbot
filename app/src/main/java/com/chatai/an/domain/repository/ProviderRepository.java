package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.ApiKeyEntry;
import com.chatai.an.domain.model.ProviderConfig;

import java.util.List;

public interface ProviderRepository {
    LiveData<List<ProviderConfig>> observeProviderConfigs();

    List<ProviderConfig> getAllProviderConfigsSync();

    ProviderConfig getProviderConfigSync(String providerId);

    void saveProviderConfig(ProviderConfig config);

    LiveData<List<ApiKeyEntry>> observeApiKeys(String providerId);

    List<ApiKeyEntry> getApiKeysSync(String providerId);

    boolean addApiKey(String providerId, String label, String rawApiKey);

    void setKeyEnabled(String keyId, boolean enabled);

    void setActiveKey(String providerId, String keyId);

    void deleteApiKey(String keyId);

    String getActiveDecryptedKeySync(String providerId);

    String rotateToNextAvailableKeySync(String providerId, String failedKeyId, String reason, long cooldownMillis);

    String getActiveKeyIdSync(String providerId);
}
