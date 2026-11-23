package com.chatai.an.presentation.provider;

import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.chatai.an.ai.local.DeviceCapabilityChecker;
import com.chatai.an.ai.local.LocalAiManager;
import com.chatai.an.ai.provider.AiProvider;
import com.chatai.an.ai.provider.FreeAccessManager;
import com.chatai.an.ai.provider.ProviderFactory;
import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.ApiKeyEntry;
import com.chatai.an.domain.model.DeviceCompatibility;
import com.chatai.an.domain.model.FreeProviderStatus;
import com.chatai.an.domain.model.LocalModelInfo;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.model.ThemeConfig;
import com.chatai.an.domain.repository.LocalModelRepository;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.ProviderRepository;
import com.chatai.an.domain.repository.ThemeRepository;

import java.util.List;
import java.util.Map;

public class ProviderSettingsViewModel extends ViewModel {
    private final ProviderRepository providerRepository;
    private final ModelRepository modelRepository;
    private final LocalModelRepository localModelRepository;
    private final ThemeRepository themeRepository;
    private final ProviderFactory providerFactory;
    private final FreeAccessManager freeAccessManager;
    private final LocalAiManager localAiManager;
    private final DeviceCapabilityChecker capabilityChecker;
    private final AppExecutors executors;

    private final MutableLiveData<String> selectedProviderId = new MutableLiveData<>(ModelProvider.GOOGLE_GEMINI.getId());
    private final MutableLiveData<String> statusMessage = new MutableLiveData<>(null);
    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);

    public ProviderSettingsViewModel(
            ProviderRepository providerRepository,
            ModelRepository modelRepository,
            LocalModelRepository localModelRepository,
            ThemeRepository themeRepository,
            ProviderFactory providerFactory,
            FreeAccessManager freeAccessManager,
            LocalAiManager localAiManager,
            DeviceCapabilityChecker capabilityChecker,
            AppExecutors executors
    ) {
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
        this.localModelRepository = localModelRepository;
        this.themeRepository = themeRepository;
        this.providerFactory = providerFactory;
        this.freeAccessManager = freeAccessManager;
        this.localAiManager = localAiManager;
        this.capabilityChecker = capabilityChecker;
        this.executors = executors;
    }

    public LiveData<ThemeConfig> getActiveTheme() {
        return themeRepository.observeActiveTheme();
    }

    public LiveData<List<ProviderConfig>> getProviderConfigs() {
        return providerRepository.observeProviderConfigs();
    }

    public LiveData<Map<ModelProvider, FreeProviderStatus>> getFreeProviderStatuses() {
        return modelRepository.observeFreeProviderStatuses();
    }

    public LiveData<List<LocalModelInfo>> getLocalModels() {
        return localModelRepository.observeLocalModels();
    }

    public LiveData<String> getSelectedProviderId() {
        return selectedProviderId;
    }

    public LiveData<String> getStatusMessage() {
        return statusMessage;
    }

    public LiveData<Boolean> isBusy() {
        return busy;
    }

    public void selectProvider(String providerId) {
        selectedProviderId.setValue(providerId);
    }

    public LiveData<List<ApiKeyEntry>> observeKeysForProvider(String providerId) {
        return providerRepository.observeApiKeys(providerId);
    }

    public DeviceCompatibility inspectDeviceCompatibility() {
        return capabilityChecker.inspect();
    }

    public List<AiModel> getAllModels() {
        return modelRepository.getAllModels();
    }

    public void addApiKey(String providerId, String label, String rawKey) {
        executors.diskIO().execute(() -> {
            boolean ok = providerRepository.addApiKey(providerId, label, rawKey);
            statusMessage.postValue(ok ? "Encrypted API key added" : "Invalid or empty API key");
            if (ok && ModelProvider.OPENROUTER_FREE.getId().equals(providerId)) {
                freeAccessManager.refreshFreeCatalogs(null);
            }
        });
    }

    public void setKeyEnabled(String keyId, boolean enabled) {
        providerRepository.setKeyEnabled(keyId, enabled);
        if (ModelProvider.OPENROUTER_FREE.getId().equals(selectedProviderId.getValue())) {
            freeAccessManager.refreshFreeCatalogs(null);
        }
    }

    public void setActiveKey(String providerId, String keyId) {
        providerRepository.setActiveKey(providerId, keyId);
        if (ModelProvider.OPENROUTER_FREE.getId().equals(providerId)) {
            freeAccessManager.refreshFreeCatalogs(null);
        }
    }

    public void deleteKey(String keyId) {
        providerRepository.deleteApiKey(keyId);
        if (ModelProvider.OPENROUTER_FREE.getId().equals(selectedProviderId.getValue())) {
            freeAccessManager.refreshFreeCatalogs(null);
        }
    }

    public void updateProviderConfig(
            String providerId,
            String baseUrl,
            boolean enabled,
            boolean keyRotationEnabled,
            String fallbackModelId
    ) {
        executors.diskIO().execute(() -> {
            ProviderConfig existing = providerRepository.getProviderConfigSync(providerId);
            if (existing == null) return;
            ProviderConfig updated = new ProviderConfig(
                    existing.getProviderId(),
                    existing.getDisplayName(),
                    existing.getProviderType(),
                    existing.getProtocolType(),
                    baseUrl != null ? baseUrl.trim() : existing.getBaseUrl(),
                    enabled,
                    keyRotationEnabled,
                    fallbackModelId != null ? fallbackModelId.trim() : "",
                    existing.getCustomHeaders(),
                    existing.getLastConnectionStatus(),
                    existing.getLastTestedAt()
            );
            providerRepository.saveProviderConfig(updated);
            statusMessage.postValue("Saved settings for " + updated.getDisplayName());
        });
    }

    public void testProviderConnection(String providerId) {
        busy.setValue(true);
        executors.worker().execute(() -> {
            try {
                ProviderConfig cfg = providerRepository.getProviderConfigSync(providerId);
                ModelProvider mp = cfg != null ? cfg.getProviderType() : ModelProvider.fromId(providerId);
                AiProvider provider = providerFactory.create(mp, cfg);
                String key = providerRepository.getActiveDecryptedKeySync(providerId);
                String result = provider.testConnection(key);

                List<AiModel> fetched = provider.fetchModels(key);
                if (fetched != null && !fetched.isEmpty()) {
                    modelRepository.upsertDynamicModels(mp, fetched);
                }

                if (cfg != null) {
                    providerRepository.saveProviderConfig(new ProviderConfig(
                            cfg.getProviderId(),
                            cfg.getDisplayName(),
                            cfg.getProviderType(),
                            cfg.getProtocolType(),
                            cfg.getBaseUrl(),
                            cfg.isEnabled(),
                            cfg.isKeyRotationEnabled(),
                            cfg.getFallbackModelId(),
                            cfg.getCustomHeaders(),
                            result,
                            System.currentTimeMillis()
                    ));
                }
                statusMessage.postValue(result);
            } catch (Exception e) {
                statusMessage.postValue("Connection failed: " + e.getMessage());
            } finally {
                busy.postValue(false);
            }
        });
    }

    public void refreshFreeCatalogs() {
        busy.setValue(true);
        freeAccessManager.refreshFreeCatalogs((count, err) -> {
            busy.setValue(false);
            if (err != null) {
                statusMessage.setValue("Synced " + count + " free models (warnings: " + err + ")");
            } else {
                statusMessage.setValue("Synced " + count + " verified free models");
            }
        });
    }

    public void importLocalModel(Uri uri) {
        if (uri == null) return;
        executors.diskIO().execute(() -> {
            LocalModelInfo info = localAiManager.importModelFromUri(uri);
            if (info != null) {
                statusMessage.postValue("Indexed local model: " + info.getDisplayName());
            }
        });
    }

    public void deleteLocalModel(String id) {
        localModelRepository.deleteLocalModel(id);
    }

    public void clearStatusMessage() {
        statusMessage.setValue(null);
    }
}
