package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.LocalModelInfo;

import java.util.List;

public interface LocalModelRepository {
    LiveData<List<LocalModelInfo>> observeLocalModels();

    List<LocalModelInfo> getLocalModelsSync();

    void saveLocalModel(LocalModelInfo info);

    void deleteLocalModel(String id);
}
