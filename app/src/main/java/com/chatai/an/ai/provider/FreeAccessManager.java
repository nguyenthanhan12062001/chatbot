package com.chatai.an.ai.provider;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.FreeProviderStatus;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.repository.ModelRepository;
import com.chatai.an.domain.repository.ProviderRepository;

import java.util.List;
import java.util.Map;

/**
 * Coordinates Free Cloud AI sources (Pollinations & Kilo), catalog sync, rate-limit tracking, and availability.
 */
public class FreeAccessManager {
    private final ModelRepository modelRepository;
    private final ProviderRepository providerRepository;
    private final ProviderFactory providerFactory;
    private final AppExecutors executors;

    public FreeAccessManager(
            ModelRepository modelRepository,
            ProviderRepository providerRepository,
            ProviderFactory providerFactory,
            AppExecutors executors
    ) {
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
        this.providerFactory = providerFactory;
        this.executors = executors;
    }

    public boolean isInCooldown(ModelProvider provider) {
        Map<ModelProvider, FreeProviderStatus> map = modelRepository.observeFreeProviderStatuses().getValue();
        if (map == null) return false;
        FreeProviderStatus status = map.get(provider);
        return status != null && status.getRetryAtMillis() > System.currentTimeMillis();
    }

    public void recordSuccess(ModelProvider provider, int modelCount) {
        if (provider == null) return;
        modelRepository.updateFreeProviderStatus(new FreeProviderStatus(
                provider,
                FreeProviderStatus.State.AVAILABLE,
                "Available",
                0L,
                modelCount,
                System.currentTimeMillis()
        ));
    }

    public void recordError(ModelProvider provider, AiError error) {
        if (provider == null || error == null) return;
        FreeProviderStatus.State state;
        long cooldownMs;
        if (error.getType() == AiError.ErrorType.QUOTA_EXCEEDED) {
            state = FreeProviderStatus.State.QUOTA_EXCEEDED;
            cooldownMs = Math.max(60_000L, error.getRetryAfterSeconds() * 1000L);
        } else if (error.getType() == AiError.ErrorType.RATE_LIMIT) {
            state = FreeProviderStatus.State.RATE_LIMITED;
            cooldownMs = Math.max(20_000L, error.getRetryAfterSeconds() * 1000L);
        } else {
            state = FreeProviderStatus.State.TEMPORARILY_UNAVAILABLE;
            cooldownMs = 15_000L;
        }
        long now = System.currentTimeMillis();
        modelRepository.updateFreeProviderStatus(new FreeProviderStatus(
                provider,
                state,
                error.getMessage(),
                now + cooldownMs,
                countModels(provider),
                now
        ));
    }

    public void refreshFreeCatalogs(ModelRepository.OnCatalogRefreshListener listener) {
        executors.worker().execute(() -> {
            int totalFetched = 0;
            StringBuilder errors = new StringBuilder();
            ModelProvider[] freeProviders = new ModelProvider[]{
                    ModelProvider.POLLINATIONS,
                    ModelProvider.KILO,
                    ModelProvider.OPENROUTER_FREE
            };
            for (ModelProvider fp : freeProviders) {
                try {
                    ProviderConfig cfg = providerRepository.getProviderConfigSync(fp.getId());
                    AiProvider provider = providerFactory.create(fp, cfg);
                    String optionalKey = providerRepository.getActiveDecryptedKeySync(fp.getId());
                    List<AiModel> fetched = provider.fetchModels(optionalKey != null ? optionalKey : "");
                    if (fetched != null && !fetched.isEmpty()) {
                        modelRepository.upsertDynamicModels(fp, fetched);
                        totalFetched += fetched.size();
                        recordSuccess(fp, fetched.size());
                    } else {
                        recordSuccess(fp, countModels(fp));
                    }
                } catch (Exception e) {
                    if (errors.length() > 0) errors.append("; ");
                    errors.append(fp.getDisplayName()).append(": ").append(e.getMessage());
                    modelRepository.updateFreeProviderStatus(new FreeProviderStatus(
                            fp,
                            FreeProviderStatus.State.TEMPORARILY_UNAVAILABLE,
                            e.getMessage() != null ? e.getMessage() : "Sync error",
                            System.currentTimeMillis() + 30_000L,
                            countModels(fp),
                            System.currentTimeMillis()
                    ));
                }
            }
            int finalTotal = totalFetched;
            String errMsg = errors.length() > 0 ? errors.toString() : null;
            if (listener != null) {
                executors.postToMain(() -> listener.onRefreshed(finalTotal, errMsg));
            }
        });
    }

    private int countModels(ModelProvider provider) {
        int c = 0;
        for (AiModel m : modelRepository.getAllModels()) {
            if (m.getProvider() == provider) c++;
        }
        return c;
    }
}
