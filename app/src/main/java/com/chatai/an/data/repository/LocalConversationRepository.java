package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.ConversationDao;
import com.chatai.an.data.local.db.ConversationEntity;
import com.chatai.an.data.local.db.EntityMapper;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.repository.ConversationRepository;

import java.util.List;
import java.util.UUID;

public class LocalConversationRepository implements ConversationRepository {
    private final ConversationDao conversationDao;
    private final AppExecutors executors;

    public LocalConversationRepository(ConversationDao conversationDao, AppExecutors executors) {
        this.conversationDao = conversationDao;
        this.executors = executors;
    }

    @Override
    public LiveData<List<Conversation>> observeAllConversations() {
        return Transformations.map(conversationDao.observeAll(), EntityMapper::toConversationList);
    }

    @Override
    public LiveData<List<Conversation>> searchConversations(String query) {
        String sanitized = query == null ? "" : query.trim();
        if (sanitized.isEmpty()) {
            return observeAllConversations();
        }
        return Transformations.map(conversationDao.search(sanitized), EntityMapper::toConversationList);
    }

    @Override
    public Conversation getConversationSync(String conversationId) {
        if (conversationId == null || conversationId.trim().isEmpty()) return null;
        ConversationEntity entity = conversationDao.getByIdSync(conversationId.trim());
        return entity != null ? EntityMapper.toDomain(entity) : null;
    }

    @Override
    public void createConversation(String title, String modelId, OnConversationCreatedListener listener) {
        executors.diskIO().execute(() -> {
            long now = System.currentTimeMillis();
            String id = UUID.randomUUID().toString();
            String finalTitle = (title == null || title.trim().isEmpty()) ? "" : title.trim();
            ConversationEntity entity = new ConversationEntity(
                    id,
                    finalTitle,
                    modelId,
                    now,
                    now,
                    ""
            );
            conversationDao.insert(entity);
            Conversation domain = EntityMapper.toDomain(entity);
            if (listener != null) {
                executors.postToMain(() -> listener.onCreated(domain));
            }
        });
    }

    @Override
    public void renameConversation(String conversationId, String newTitle) {
        if (conversationId == null || newTitle == null || newTitle.trim().isEmpty()) return;
        executors.diskIO().execute(() ->
                conversationDao.updateTitle(conversationId, newTitle.trim(), System.currentTimeMillis())
        );
    }

    @Override
    public void updateConversationProject(String conversationId, String projectId) {
        if (conversationId == null) return;
        String safeProj = projectId != null ? projectId.trim() : "";
        conversationDao.updateProjectId(conversationId, safeProj, System.currentTimeMillis());
    }

    @Override
    public void updateConversationCompaction(String conversationId, String summary, long compactedUntil) {
        if (conversationId == null) return;
        String safeSummary = summary != null ? summary.trim() : "";
        conversationDao.updateCompaction(conversationId, safeSummary, compactedUntil);
    }

    @Override
    public void deleteConversation(String conversationId) {
        if (conversationId == null) return;
        executors.diskIO().execute(() -> conversationDao.deleteById(conversationId));
    }

    @Override
    public void deleteAllConversations() {
        executors.diskIO().execute(conversationDao::deleteAll);
    }

    @Override
    public void updateConversationModel(String conversationId, String modelId) {
        if (conversationId == null || modelId == null) return;
        executors.diskIO().execute(() ->
                conversationDao.updateModel(conversationId, modelId, System.currentTimeMillis())
        );
    }
}
