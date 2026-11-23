package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.ApiKeyDao;
import com.chatai.an.data.local.db.ApiKeyEntity;
import com.chatai.an.data.local.db.EntityMapper;
import com.chatai.an.data.local.db.ProviderConfigDao;
import com.chatai.an.data.local.db.ProviderConfigEntity;
import com.chatai.an.data.security.KeyCipher;
import com.chatai.an.domain.model.ApiKeyEntry;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.repository.ProviderRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class LocalProviderRepository implements ProviderRepository {
    private final ProviderConfigDao configDao;
    private final ApiKeyDao apiKeyDao;
    private final KeyCipher cipher;
    private final AppExecutors executors;

    public LocalProviderRepository(
            ProviderConfigDao configDao,
            ApiKeyDao apiKeyDao,
            KeyCipher cipher,
            AppExecutors executors
    ) {
        this.configDao = configDao;
        this.apiKeyDao = apiKeyDao;
        this.cipher = cipher;
        this.executors = executors;
        seedDefaultConfigsAsync();
    }

    private void seedDefaultConfigsAsync() {
        executors.diskIO().execute(this::ensureDefaultConfigsSync);
    }

    private void ensureDefaultConfigsSync() {
        List<ProviderConfigEntity> existing = configDao.getAllSync();
        if (existing != null && existing.size() >= ModelProvider.values().length) {
            return;
        }
        List<ProviderConfigEntity> defaults = new ArrayList<>();
        for (ModelProvider p : ModelProvider.values()) {
            ProviderConfig cfg = new ProviderConfig(
                    p.getId(),
                    p.getDisplayName(),
                    p,
                    p.getDefaultProtocol(),
                    p.getDefaultBaseUrl(),
                    true,
                    true,
                    "",
                    Collections.emptyMap(),
                    "",
                    0L
            );
            defaults.add(EntityMapper.toEntity(cfg));
        }
        configDao.insertAllIgnore(defaults);
    }

    @Override
    public LiveData<List<ProviderConfig>> observeProviderConfigs() {
        return Transformations.map(configDao.observeAll(), EntityMapper::toProviderConfigList);
    }

    @Override
    public List<ProviderConfig> getAllProviderConfigsSync() {
        ensureDefaultConfigsSync();
        return EntityMapper.toProviderConfigList(configDao.getAllSync());
    }

    @Override
    public ProviderConfig getProviderConfigSync(String providerId) {
        if (providerId == null) return null;
        ensureDefaultConfigsSync();
        ProviderConfigEntity entity = configDao.getByIdSync(providerId);
        if (entity != null) {
            return EntityMapper.toDomain(entity);
        }
        ModelProvider p = ModelProvider.fromId(providerId);
        return new ProviderConfig(
                p.getId(),
                p.getDisplayName(),
                p,
                p.getDefaultProtocol(),
                p.getDefaultBaseUrl(),
                true,
                true,
                "",
                Collections.emptyMap(),
                "",
                0L
        );
    }

    @Override
    public void saveProviderConfig(ProviderConfig config) {
        if (config == null) return;
        executors.diskIO().execute(() -> configDao.upsert(EntityMapper.toEntity(config)));
    }

    @Override
    public LiveData<List<ApiKeyEntry>> observeApiKeys(String providerId) {
        return Transformations.map(apiKeyDao.observeByProvider(providerId), EntityMapper::toApiKeyEntryList);
    }

    @Override
    public List<ApiKeyEntry> getApiKeysSync(String providerId) {
        return EntityMapper.toApiKeyEntryList(apiKeyDao.getByProviderSync(providerId));
    }

    @Override
    public boolean addApiKey(String providerId, String label, String rawApiKey) {
        if (providerId == null || rawApiKey == null) return false;
        String trimmedKey = rawApiKey.trim();
        if (trimmedKey.isEmpty()) return false;
        String encrypted = cipher.encrypt(trimmedKey);
        if (encrypted == null || encrypted.isEmpty()) return false;

        String masked = KeyCipher.maskKey(trimmedKey);
        String safeLabel = (label == null || label.trim().isEmpty()) ? "API Key" : label.trim();
        List<ApiKeyEntity> current = apiKeyDao.getByProviderSync(providerId);
        boolean shouldBeActive = current == null || current.isEmpty();

        ApiKeyEntity entity = new ApiKeyEntity(
                UUID.randomUUID().toString(),
                providerId,
                safeLabel,
                encrypted,
                masked,
                true,
                shouldBeActive,
                "",
                0L,
                System.currentTimeMillis()
        );
        apiKeyDao.insert(entity);
        return true;
    }

    @Override
    public void setKeyEnabled(String keyId, boolean enabled) {
        if (keyId == null) return;
        executors.diskIO().execute(() -> apiKeyDao.setEnabled(keyId, enabled));
    }

    @Override
    public void setActiveKey(String providerId, String keyId) {
        if (providerId == null || keyId == null) return;
        executors.diskIO().execute(() -> apiKeyDao.setActiveForProvider(providerId, keyId));
    }

    @Override
    public void deleteApiKey(String keyId) {
        if (keyId == null) return;
        executors.diskIO().execute(() -> apiKeyDao.deleteById(keyId));
    }

    @Override
    public String getActiveKeyIdSync(String providerId) {
        if (providerId == null) return null;
        ApiKeyEntity candidate = pickBestKeySync(providerId, null);
        return candidate != null ? candidate.id : null;
    }

    @Override
    public String getActiveDecryptedKeySync(String providerId) {
        if (providerId == null) return null;
        ApiKeyEntity candidate = pickBestKeySync(providerId, null);
        if (candidate == null) return null;
        return cipher.decrypt(candidate.encryptedKey);
    }

    @Override
    public String rotateToNextAvailableKeySync(String providerId, String failedKeyId, String reason, long cooldownMillis) {
        if (providerId == null) return null;
        long now = System.currentTimeMillis();
        if (failedKeyId != null) {
            long cooldownUntil = now + Math.max(5000L, cooldownMillis);
            apiKeyDao.updateFailureStatus(failedKeyId, reason != null ? reason : "Error", cooldownUntil);
        }
        ApiKeyEntity next = pickBestKeySync(providerId, failedKeyId);
        if (next == null) return null;
        apiKeyDao.setActiveForProvider(providerId, next.id);
        return cipher.decrypt(next.encryptedKey);
    }

    private ApiKeyEntity pickBestKeySync(String providerId, String excludeKeyId) {
        List<ApiKeyEntity> keys = apiKeyDao.getByProviderSync(providerId);
        if (keys == null || keys.isEmpty()) return null;
        long now = System.currentTimeMillis();

        // 1. Prefer active, enabled, not in cooldown, not excluded
        for (ApiKeyEntity k : keys) {
            if (k.active && k.enabled && k.cooldownUntil <= now && !k.id.equals(excludeKeyId)) {
                return k;
            }
        }
        // 2. Otherwise any enabled key not in cooldown and not excluded
        for (ApiKeyEntity k : keys) {
            if (k.enabled && k.cooldownUntil <= now && !k.id.equals(excludeKeyId)) {
                return k;
            }
        }
        // 3. Fallback: any enabled key not excluded if caller isn't excluding it
        if (excludeKeyId == null) {
            for (ApiKeyEntity k : keys) {
                if (k.enabled) return k;
            }
        }
        return null;
    }
}
