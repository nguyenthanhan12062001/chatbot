package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.EntityMapper;
import com.chatai.an.data.local.db.LocalModelDao;
import com.chatai.an.domain.model.LocalModelInfo;
import com.chatai.an.domain.repository.LocalModelRepository;

import java.util.List;

public class LocalModelStoreRepository implements LocalModelRepository {
    private final LocalModelDao dao;
    private final AppExecutors executors;

    public LocalModelStoreRepository(LocalModelDao dao, AppExecutors executors) {
        this.dao = dao;
        this.executors = executors;
    }

    @Override
    public LiveData<List<LocalModelInfo>> observeLocalModels() {
        return Transformations.map(dao.observeAll(), EntityMapper::toLocalModelList);
    }

    @Override
    public List<LocalModelInfo> getLocalModelsSync() {
        return EntityMapper.toLocalModelList(dao.getAllSync());
    }

    @Override
    public void saveLocalModel(LocalModelInfo info) {
        if (info == null) return;
        executors.diskIO().execute(() -> dao.upsert(EntityMapper.toEntity(info)));
    }

    @Override
    public void deleteLocalModel(String id) {
        if (id == null) return;
        executors.diskIO().execute(() -> dao.deleteById(id));
    }
}
