package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.AgentTask;

import java.util.List;

public interface AgentRepository {
    LiveData<List<AgentTask>> observeAllTasks();

    void saveTask(AgentTask task);

    void deleteTask(String taskId);
}
