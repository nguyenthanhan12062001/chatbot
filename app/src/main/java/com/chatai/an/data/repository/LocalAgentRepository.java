package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.AgentTaskDao;
import com.chatai.an.data.local.db.EntityMapper;
import com.chatai.an.domain.model.AgentTask;
import com.chatai.an.domain.repository.AgentRepository;

import java.util.List;

public class LocalAgentRepository implements AgentRepository {
    private final AgentTaskDao dao;
    private final AppExecutors executors;

    public LocalAgentRepository(AgentTaskDao dao, AppExecutors executors) {
        this.dao = dao;
        this.executors = executors;
    }

    @Override
    public LiveData<List<AgentTask>> observeAllTasks() {
        return Transformations.map(dao.observeAll(), EntityMapper::toAgentTaskList);
    }

    @Override
    public void saveTask(AgentTask task) {
        if (task == null) return;
        executors.diskIO().execute(() -> dao.upsert(EntityMapper.toEntity(task)));
    }

    @Override
    public void deleteTask(String taskId) {
        if (taskId == null) return;
        executors.diskIO().execute(() -> dao.deleteById(taskId));
    }
}
