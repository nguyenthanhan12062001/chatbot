package com.chatai.an.data.repository;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Transformations;

import com.chatai.an.core.AppExecutors;
import com.chatai.an.data.local.db.AttachmentDao;
import com.chatai.an.data.local.db.AttachmentEntity;
import com.chatai.an.data.local.db.ChatDatabase;
import com.chatai.an.data.local.db.ConversationDao;
import com.chatai.an.data.local.db.ConversationEntity;
import com.chatai.an.data.local.db.EntityMapper;
import com.chatai.an.data.local.db.MessageDao;
import com.chatai.an.data.local.db.MessageEntity;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.SourceItem;
import com.chatai.an.domain.model.TimelineEntry;
import com.chatai.an.domain.model.TokenUsage;
import com.chatai.an.domain.model.ToolCallRecord;
import com.chatai.an.domain.repository.MessageRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class LocalMessageRepository implements MessageRepository {
    private final ChatDatabase database;
    private final ConversationDao conversationDao;
    private final MessageDao messageDao;
    private final AttachmentDao attachmentDao;
    private final AppExecutors executors;

    public LocalMessageRepository(ChatDatabase database, AppExecutors executors) {
        this.database = database;
        this.conversationDao = database.conversationDao();
        this.messageDao = database.messageDao();
        this.attachmentDao = database.attachmentDao();
        this.executors = executors;
    }

    @Override
    public LiveData<List<Message>> observeMessages(String conversationId) {
        return Transformations.map(
                messageDao.observeByConversation(conversationId),
                EntityMapper::toMessageList
        );
    }

    @Override
    public List<Message> getMessagesSync(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return Collections.emptyList();
        }
        return EntityMapper.toMessageList(messageDao.getByConversationSync(conversationId));
    }

    @Override
    public void sendUserMessage(
            String conversationId,
            String content,
            String modelId,
            boolean deepThinkingEnabled,
            List<Attachment> pendingAttachments,
            boolean autoTitleConversation,
            OnMessageSavedListener listener
    ) {
        executors.diskIO().execute(() -> {
            long now = System.currentTimeMillis();
            String safeContent = content == null ? "" : content.trim();
            List<Attachment> attachments = pendingAttachments == null
                    ? Collections.emptyList()
                    : new ArrayList<>(pendingAttachments);

            String targetConversationId = conversationId;
            database.runInTransaction(() -> {
                ConversationEntity conv = targetConversationId != null
                        ? conversationDao.getByIdSync(targetConversationId)
                        : null;
                String resolvedConvId;
                if (conv == null) {
                    resolvedConvId = targetConversationId != null && !targetConversationId.isEmpty()
                            ? targetConversationId
                            : UUID.randomUUID().toString();
                    String autoTitle = autoTitleConversation
                            ? buildTitleFromInput(safeContent, attachments)
                            : "";
                    conv = new ConversationEntity(
                            resolvedConvId,
                            autoTitle,
                            modelId,
                            now,
                            now,
                            buildPreview(safeContent, attachments)
                    );
                    conversationDao.insert(conv);
                } else {
                    if (autoTitleConversation && conv.title.trim().isEmpty()) {
                        conversationDao.updateTitle(conv.id, buildTitleFromInput(safeContent, attachments), now);
                    }
                    conversationDao.updatePreview(conv.id, buildPreview(safeContent, attachments), now);
                }

                String messageId = UUID.randomUUID().toString();
                MessageEntity messageEntity = new MessageEntity(
                        messageId,
                        conv.id,
                        MessageRole.USER.name(),
                        safeContent,
                        "",
                        modelId != null ? modelId : "",
                        "",
                        "",
                        deepThinkingEnabled,
                        0,
                        0,
                        0,
                        false,
                        now,
                        now,
                        "[]",
                        "[]"
                );
                messageDao.insert(messageEntity);

                if (!attachments.isEmpty()) {
                    List<AttachmentEntity> attachmentEntities = new ArrayList<>();
                    for (Attachment att : attachments) {
                        attachmentEntities.add(EntityMapper.toEntity(att, messageId));
                    }
                    attachmentDao.insertAll(attachmentEntities);
                }

                Message saved = new Message(
                        messageId,
                        conv.id,
                        MessageRole.USER,
                        safeContent,
                        modelId,
                        deepThinkingEnabled,
                        now,
                        now,
                        attachments
                );
                if (listener != null) {
                    executors.postToMain(() -> listener.onSaved(saved));
                }
            });
        });
    }

    @Override
    public void saveAssistantMessage(
            String conversationId,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            TokenUsage tokenUsage,
            boolean isError,
            OnMessageSavedListener listener
    ) {
        saveAssistantMessage(
                conversationId,
                content,
                thinkingContent,
                modelId,
                providerId,
                accessType,
                tokenUsage,
                isError,
                Collections.emptyList(),
                Collections.emptyList(),
                listener
        );
    }

    @Override
    public void saveAssistantMessage(
            String conversationId,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            TokenUsage tokenUsage,
            boolean isError,
            List<ToolCallRecord> toolRecords,
            List<String> followUpSuggestions,
            OnMessageSavedListener listener
    ) {
        saveAssistantMessage(
                conversationId,
                content,
                thinkingContent,
                modelId,
                providerId,
                accessType,
                tokenUsage,
                isError,
                toolRecords,
                followUpSuggestions,
                Collections.emptyList(),
                Collections.emptyList(),
                listener
        );
    }

    @Override
    public void saveAssistantMessage(
            String conversationId,
            String content,
            String thinkingContent,
            String modelId,
            String providerId,
            AccessType accessType,
            TokenUsage tokenUsage,
            boolean isError,
            List<ToolCallRecord> toolRecords,
            List<String> followUpSuggestions,
            List<TimelineEntry> timelineEntries,
            List<SourceItem> sources,
            OnMessageSavedListener listener
    ) {
        if (conversationId == null || conversationId.isEmpty()) return;
        List<ToolCallRecord> safeTools = toolRecords == null ? Collections.emptyList() : new ArrayList<>(toolRecords);
        List<String> safeFollowUps = followUpSuggestions == null ? Collections.emptyList() : new ArrayList<>(followUpSuggestions);
        List<TimelineEntry> safeTimeline = timelineEntries == null ? Collections.emptyList() : new ArrayList<>(timelineEntries);
        List<SourceItem> safeSources = sources == null ? Collections.emptyList() : new ArrayList<>(sources);
        executors.diskIO().execute(() -> {
            long now = System.currentTimeMillis();
            String safeContent = content == null ? "" : content;
            String safeThinking = thinkingContent == null ? "" : thinkingContent;
            int prompt = tokenUsage != null ? tokenUsage.getPromptTokens() : 0;
            int completion = tokenUsage != null ? tokenUsage.getCompletionTokens() : 0;
            int total = tokenUsage != null ? tokenUsage.getTotalTokens() : 0;
            String accessStr = accessType != null ? accessType.name() : "";
            String messageId = UUID.randomUUID().toString();
            String toolsJson = EntityMapper.toToolRecordsJson(safeTools);
            String followUpsJson = EntityMapper.toStringListJson(safeFollowUps);
            String timelineJson = EntityMapper.toTimelineJson(safeTimeline);
            String sourcesJson = EntityMapper.toSourcesJson(safeSources);

            database.runInTransaction(() -> {
                MessageEntity entity = new MessageEntity(
                        messageId,
                        conversationId,
                        MessageRole.ASSISTANT.name(),
                        safeContent,
                        safeThinking,
                        modelId != null ? modelId : "",
                        providerId != null ? providerId : "",
                        accessStr,
                        !safeThinking.isEmpty(),
                        prompt,
                        completion,
                        total,
                        isError,
                        now,
                        now,
                        toolsJson,
                        followUpsJson,
                        timelineJson,
                        sourcesJson
                );
                messageDao.insert(entity);
                conversationDao.updatePreview(conversationId, buildPreview(safeContent, Collections.emptyList()), now);
            });

            Message saved = new Message(
                    messageId,
                    conversationId,
                    MessageRole.ASSISTANT,
                    safeContent,
                    safeThinking,
                    modelId,
                    providerId,
                    accessType,
                    !safeThinking.isEmpty(),
                    tokenUsage,
                    isError,
                    now,
                    now,
                    Collections.emptyList(),
                    safeTools,
                    safeFollowUps,
                    safeTimeline,
                    safeSources
            );
            if (listener != null) {
                executors.postToMain(() -> listener.onSaved(saved));
            }
        });
    }

    @Override
    public void editMessage(String messageId, String newContent) {
        if (messageId == null || newContent == null) return;
        String trimmed = newContent.trim();
        executors.diskIO().execute(() -> database.runInTransaction(() -> {
            MessageEntity existing = messageDao.getByIdSync(messageId);
            if (existing == null) return;
            long now = System.currentTimeMillis();
            messageDao.updateContent(messageId, trimmed, now);
            MessageEntity latest = messageDao.getLatestByConversationSync(existing.conversationId);
            if (latest != null && latest.id.equals(messageId)) {
                conversationDao.updatePreview(existing.conversationId, buildPreview(trimmed, Collections.emptyList()), now);
            }
        }));
    }

    @Override
    public void deleteMessage(String messageId, String conversationId) {
        if (messageId == null) return;
        executors.diskIO().execute(() -> database.runInTransaction(() -> {
            messageDao.deleteById(messageId);
            if (conversationId != null) {
                MessageEntity latest = messageDao.getLatestByConversationSync(conversationId);
                String preview = latest != null ? buildPreview(latest.content, Collections.emptyList()) : "";
                conversationDao.updatePreview(conversationId, preview, System.currentTimeMillis());
            }
        }));
    }

    @Override
    public void deleteMessagesFromTimestamp(String conversationId, long fromCreatedAt, Runnable onComplete) {
        if (conversationId == null || conversationId.isEmpty()) {
            if (onComplete != null) executors.postToMain(onComplete);
            return;
        }
        executors.diskIO().execute(() -> {
            database.runInTransaction(() -> {
                messageDao.deleteFromTimestampSync(conversationId, fromCreatedAt);
                MessageEntity latest = messageDao.getLatestByConversationSync(conversationId);
                String preview = latest != null ? buildPreview(latest.content, Collections.emptyList()) : "";
                conversationDao.updatePreview(conversationId, preview, System.currentTimeMillis());
            });
            if (onComplete != null) {
                executors.postToMain(onComplete);
            }
        });
    }

    private String buildTitleFromInput(String content, List<Attachment> attachments) {
        if (!content.isEmpty()) {
            String singleLine = content.replaceAll("\\s+", " ").trim();
            return singleLine.length() > 42 ? singleLine.substring(0, 42) + "..." : singleLine;
        }
        if (attachments != null && !attachments.isEmpty()) {
            return attachments.get(0).getDisplayName();
        }
        return "Chat";
    }

    private String buildPreview(String content, List<Attachment> attachments) {
        if (!content.isEmpty()) {
            String singleLine = content.replaceAll("\\s+", " ").trim();
            return singleLine.length() > 72 ? singleLine.substring(0, 72) + "..." : singleLine;
        }
        if (attachments != null && !attachments.isEmpty()) {
            return "[" + attachments.size() + " attachment(s)]";
        }
        return "";
    }
}
