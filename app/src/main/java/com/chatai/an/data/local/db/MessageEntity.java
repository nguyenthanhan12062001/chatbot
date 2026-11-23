package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "messages",
        foreignKeys = @ForeignKey(
                entity = ConversationEntity.class,
                parentColumns = "id",
                childColumns = "conversation_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("conversation_id")}
)
public class MessageEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @ColumnInfo(name = "conversation_id")
    @NonNull
    public String conversationId;

    @NonNull
    public String role;

    @NonNull
    public String content;

    @ColumnInfo(name = "thinking_content")
    @NonNull
    public String thinkingContent;

    @ColumnInfo(name = "model_id")
    @NonNull
    public String modelId;

    @ColumnInfo(name = "provider_id")
    @NonNull
    public String providerId;

    @ColumnInfo(name = "access_type")
    @NonNull
    public String accessType;

    @ColumnInfo(name = "deep_thinking_enabled")
    public boolean deepThinkingEnabled;

    @ColumnInfo(name = "prompt_tokens")
    public int promptTokens;

    @ColumnInfo(name = "completion_tokens")
    public int completionTokens;

    @ColumnInfo(name = "total_tokens")
    public int totalTokens;

    @ColumnInfo(name = "is_error")
    public boolean isError;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    @Ignore
    public MessageEntity(
            @NonNull String id,
            @NonNull String conversationId,
            @NonNull String role,
            @NonNull String content,
            @NonNull String modelId,
            boolean deepThinkingEnabled,
            long createdAt,
            long updatedAt
    ) {
        this(id, conversationId, role, content, "", modelId, "", "", deepThinkingEnabled, 0, 0, 0, false, createdAt, updatedAt);
    }

    public MessageEntity(
            @NonNull String id,
            @NonNull String conversationId,
            @NonNull String role,
            @NonNull String content,
            @NonNull String thinkingContent,
            @NonNull String modelId,
            @NonNull String providerId,
            @NonNull String accessType,
            boolean deepThinkingEnabled,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            boolean isError,
            long createdAt,
            long updatedAt
    ) {
        this.id = id;
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.thinkingContent = thinkingContent;
        this.modelId = modelId;
        this.providerId = providerId;
        this.accessType = accessType;
        this.deepThinkingEnabled = deepThinkingEnabled;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.isError = isError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
