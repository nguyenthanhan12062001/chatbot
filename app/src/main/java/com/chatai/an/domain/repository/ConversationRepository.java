package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.Conversation;

import java.util.List;

public interface ConversationRepository {
    interface OnConversationCreatedListener {
        void onCreated(Conversation conversation);
    }

    LiveData<List<Conversation>> observeAllConversations();

    LiveData<List<Conversation>> searchConversations(String query);

    Conversation getConversationSync(String conversationId);

    void createConversation(String title, String modelId, OnConversationCreatedListener listener);

    void renameConversation(String conversationId, String newTitle);

    void updateConversationProject(String conversationId, String projectId);

    void updateConversationCompaction(String conversationId, String summary, long compactedUntil);

    void deleteConversation(String conversationId);

    void deleteAllConversations();

    void updateConversationModel(String conversationId, String modelId);
}
