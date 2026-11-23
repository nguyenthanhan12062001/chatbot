package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.FreeProviderStatus;
import com.chatai.an.domain.model.ModelProvider;

import java.util.List;
import java.util.Map;

public interface ModelRepository {
    interface OnCatalogRefreshListener {
        void onRefreshed(int modelCount, String errorMessage);
    }

    LiveData<List<AiModel>> observeAllModels();

    List<AiModel> getAllModels();

    Map<ModelProvider, List<AiModel>> getGroupedModels();

    AiModel getModelById(String modelId);

    LiveData<AiModel> observeSelectedModel();

    AiModel getSelectedModelSnapshot();

    void selectModel(String modelId);

    void upsertDynamicModels(ModelProvider provider, List<AiModel> fetchedModels);

    LiveData<Map<ModelProvider, FreeProviderStatus>> observeFreeProviderStatuses();

    void updateFreeProviderStatus(FreeProviderStatus status);
}
