package com.chatai.an.domain.repository;

import androidx.lifecycle.LiveData;

import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.TokenUsage;

import java.util.List;

public interface MessageRepository {
    interface OnMessageSavedListener {
        void onSaved(Message message);
    }

    LiveData<List<Message>> observeMessages(String conversationId);

    List<Message> getMessagesSync(String conversationId);

    void sendUserMessage(
            String conversationId,
            String content,
            String modelId,
            boolean deepThinkingEnabled,
            List<Attachment> pendingAttachments,
            boolean autoTitleConversation,
            OnMessageSavedListener listener
    );

    void saveAssistantMessage(
            String conversationId,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            TokenUsage tokenUsage,
            boolean isError,
            OnMessageSavedListener listener
    );

    void editMessage(String messageId, String newContent);

    void deleteMessage(String messageId, String conversationId);
}
