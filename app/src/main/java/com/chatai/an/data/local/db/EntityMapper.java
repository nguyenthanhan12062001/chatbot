package com.chatai.an.data.local.db;

import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AgentStep;
import com.chatai.an.domain.model.AgentTask;
import com.chatai.an.domain.model.AgentTaskStatus;
import com.chatai.an.domain.model.ApiKeyEntry;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.AttachmentType;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.LocalModelInfo;
import com.chatai.an.domain.model.MemoryArea;
import com.chatai.an.domain.model.MemoryCategory;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ModelProvider;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ProviderConfig;
import com.chatai.an.domain.model.SourceItem;
import com.chatai.an.domain.model.TimelineEntry;
import com.chatai.an.domain.model.TokenUsage;
import com.chatai.an.domain.model.ToolCallRecord;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class EntityMapper {
    private static final Gson GSON = new Gson();
    private static final Type MAP_STRING_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Type STEP_LIST_TYPE = new TypeToken<List<AgentStep>>() {}.getType();
    private static final Type TOOL_RECORD_LIST_TYPE = new TypeToken<List<ToolCallRecord>>() {}.getType();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type TIMELINE_LIST_TYPE = new TypeToken<List<TimelineEntry>>() {}.getType();
    private static final Type SOURCE_LIST_TYPE = new TypeToken<List<SourceItem>>() {}.getType();

    private EntityMapper() {
    }

    public static Conversation toDomain(ConversationEntity entity) {
        return new Conversation(
                entity.id,
                entity.title,
                entity.modelId,
                entity.projectId,
                entity.compactionSummary,
                entity.compactedUntil,
                entity.createdAt,
                entity.updatedAt,
                entity.lastMessagePreview
        );
    }

    public static List<Conversation> toConversationList(List<ConversationEntity> entities) {
        List<Conversation> result = new ArrayList<>();
        if (entities == null) return result;
        for (ConversationEntity entity : entities) {
            result.add(toDomain(entity));
        }
        return result;
    }

    public static Attachment toDomain(AttachmentEntity entity) {
        AttachmentType type;
        try {
            type = AttachmentType.valueOf(entity.type);
        } catch (IllegalArgumentException e) {
            type = AttachmentType.FILE;
        }
        return new Attachment(
                entity.id,
                entity.messageId,
                entity.uriString,
                entity.localPath,
                entity.displayName,
                entity.mimeType,
                entity.sizeBytes,
                type,
                entity.createdAt
        );
    }

    public static AttachmentEntity toEntity(Attachment domain, String messageId) {
        return new AttachmentEntity(
                domain.getId(),
                messageId,
                domain.getUriString(),
                domain.getLocalPath(),
                domain.getDisplayName(),
                domain.getMimeType(),
                domain.getSizeBytes(),
                domain.getType().name(),
                domain.getCreatedAt()
        );
    }

    public static Message toDomain(MessageWithAttachments relation) {
        MessageEntity entity = relation.message;
        MessageRole role;
        try {
            role = MessageRole.valueOf(entity.role);
        } catch (IllegalArgumentException e) {
            role = MessageRole.USER;
        }
        AccessType accessType = null;
        if (entity.accessType != null && !entity.accessType.isEmpty()) {
            try {
                accessType = AccessType.valueOf(entity.accessType);
            } catch (IllegalArgumentException ignored) {
            }
        }
        TokenUsage usage = null;
        if (entity.promptTokens > 0 || entity.completionTokens > 0 || entity.totalTokens > 0) {
            usage = new TokenUsage(entity.promptTokens, entity.completionTokens, entity.totalTokens);
        }
        List<Attachment> attachments = new ArrayList<>();
        if (relation.attachments != null) {
            for (AttachmentEntity attachmentEntity : relation.attachments) {
                attachments.add(toDomain(attachmentEntity));
            }
        }
        List<ToolCallRecord> toolRecords = parseToolRecords(entity.toolRecordsJson);
        List<String> followUps = parseStringList(entity.followUpsJson);
        List<TimelineEntry> timeline = parseTimelineEntries(entity.timelineJson);
        List<SourceItem> sources = parseSources(entity.sourcesJson);
        return new Message(
                entity.id,
                entity.conversationId,
                role,
                entity.content,
                entity.thinkingContent,
                entity.modelId,
                entity.providerId,
                accessType,
                entity.deepThinkingEnabled,
                usage,
                entity.isError,
                entity.createdAt,
                entity.updatedAt,
                attachments,
                toolRecords,
                followUps,
                timeline,
                sources
        );
    }

    public static String toToolRecordsJson(List<ToolCallRecord> records) {
        if (records == null || records.isEmpty()) return "[]";
        try {
            String json = GSON.toJson(records);
            return json != null ? json : "[]";
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public static String toStringListJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            String json = GSON.toJson(list);
            return json != null ? json : "[]";
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public static String toTimelineJson(List<TimelineEntry> entries) {
        if (entries == null || entries.isEmpty()) return "[]";
        try {
            String json = GSON.toJson(entries);
            return json != null ? json : "[]";
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public static String toSourcesJson(List<SourceItem> sources) {
        if (sources == null || sources.isEmpty()) return "[]";
        try {
            String json = GSON.toJson(sources);
            return json != null ? json : "[]";
        } catch (Exception ignored) {
            return "[]";
        }
    }

    private static List<ToolCallRecord> parseToolRecords(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptyList();
        try {
            List<ToolCallRecord> parsed = GSON.fromJson(json, TOOL_RECORD_LIST_TYPE);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptyList();
        try {
            List<String> parsed = GSON.fromJson(json, STRING_LIST_TYPE);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static List<TimelineEntry> parseTimelineEntries(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptyList();
        try {
            List<TimelineEntry> parsed = GSON.fromJson(json, TIMELINE_LIST_TYPE);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static List<SourceItem> parseSources(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptyList();
        try {
            List<SourceItem> parsed = GSON.fromJson(json, SOURCE_LIST_TYPE);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static List<Message> toMessageList(List<MessageWithAttachments> relations) {
        List<Message> result = new ArrayList<>();
        if (relations == null) return result;
        for (MessageWithAttachments relation : relations) {
            result.add(toDomain(relation));
        }
        return result;
    }

    public static MemoryItem toDomain(MemoryEntity entity) {
        return new MemoryItem(
                entity.id,
                MemoryCategory.fromStored(entity.category),
                MemoryScope.fromStored(entity.scope),
                MemoryArea.fromStored(entity.area),
                entity.projectId,
                entity.conversationId,
                entity.content,
                entity.importance,
                entity.enabled,
                entity.pinned,
                entity.archived,
                entity.mergedIntoId,
                entity.createdAt,
                entity.updatedAt,
                entity.lastUsedAt,
                entity.usageCount
        );
    }

    public static List<MemoryItem> toMemoryList(List<MemoryEntity> entities) {
        List<MemoryItem> result = new ArrayList<>();
        if (entities == null) return result;
        for (MemoryEntity entity : entities) {
            result.add(toDomain(entity));
        }
        return result;
    }

    public static ProviderConfig toDomain(ProviderConfigEntity entity) {
        ModelProvider providerType = ModelProvider.fromId(entity.providerType);
        ProtocolType protocolType;
        try {
            protocolType = ProtocolType.valueOf(entity.protocolType);
        } catch (IllegalArgumentException e) {
            protocolType = providerType.getDefaultProtocol();
        }
        Map<String, String> headers = Collections.emptyMap();
        if (entity.customHeadersJson != null && !entity.customHeadersJson.isEmpty()) {
            try {
                Map<String, String> parsed = GSON.fromJson(entity.customHeadersJson, MAP_STRING_TYPE);
                if (parsed != null) headers = parsed;
            } catch (Exception ignored) {
            }
        }
        return new ProviderConfig(
                entity.providerId,
                entity.displayName,
                providerType,
                protocolType,
                entity.baseUrl,
                entity.enabled,
                entity.keyRotationEnabled,
                entity.fallbackModelId,
                headers,
                entity.lastConnectionStatus,
                entity.lastTestedAt
        );
    }

    public static ProviderConfigEntity toEntity(ProviderConfig config) {
        String headersJson = config.getCustomHeaders().isEmpty()
                ? "{}"
                : GSON.toJson(config.getCustomHeaders());
        return new ProviderConfigEntity(
                config.getProviderId(),
                config.getDisplayName(),
                config.getProviderType().getId(),
                config.getProtocolType().name(),
                config.getBaseUrl(),
                config.isEnabled(),
                config.isKeyRotationEnabled(),
                config.getFallbackModelId(),
                headersJson,
                config.getLastConnectionStatus(),
                config.getLastTestedAt()
        );
    }

    public static List<ProviderConfig> toProviderConfigList(List<ProviderConfigEntity> entities) {
        List<ProviderConfig> result = new ArrayList<>();
        if (entities == null) return result;
        for (ProviderConfigEntity e : entities) {
            result.add(toDomain(e));
        }
        return result;
    }

    public static ApiKeyEntry toDomain(ApiKeyEntity entity) {
        return new ApiKeyEntry(
                entity.id,
                entity.providerId,
                entity.label,
                entity.maskedPreview,
                entity.enabled,
                entity.active,
                entity.lastError,
                entity.cooldownUntil,
                entity.createdAt
        );
    }

    public static List<ApiKeyEntry> toApiKeyEntryList(List<ApiKeyEntity> entities) {
        List<ApiKeyEntry> result = new ArrayList<>();
        if (entities == null) return result;
        for (ApiKeyEntity e : entities) {
            result.add(toDomain(e));
        }
        return result;
    }

    public static AgentTask toDomain(AgentTaskEntity entity) {
        AgentTaskStatus status;
        try {
            status = AgentTaskStatus.valueOf(entity.status);
        } catch (IllegalArgumentException e) {
            status = AgentTaskStatus.FAILED;
        }
        List<AgentStep> steps = Collections.emptyList();
        if (entity.stepsJson != null && !entity.stepsJson.isEmpty()) {
            try {
                List<AgentStep> parsed = GSON.fromJson(entity.stepsJson, STEP_LIST_TYPE);
                if (parsed != null) steps = parsed;
            } catch (Exception ignored) {
            }
        }
        return new AgentTask(
                entity.id,
                entity.goal,
                entity.conversationId,
                entity.modelId,
                status,
                steps,
                entity.finalResult,
                entity.errorMessage,
                entity.createdAt,
                entity.updatedAt
        );
    }

    public static AgentTaskEntity toEntity(AgentTask task) {
        String stepsJson = GSON.toJson(task.getSteps());
        return new AgentTaskEntity(
                task.getId(),
                task.getGoal(),
                task.getConversationId(),
                task.getModelId(),
                task.getStatus().name(),
                stepsJson != null ? stepsJson : "[]",
                task.getFinalResult(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    public static List<AgentTask> toAgentTaskList(List<AgentTaskEntity> entities) {
        List<AgentTask> result = new ArrayList<>();
        if (entities == null) return result;
        for (AgentTaskEntity e : entities) {
            result.add(toDomain(e));
        }
        return result;
    }

    public static LocalModelInfo toDomain(LocalModelEntity entity) {
        LocalModelInfo.RuntimeStatus status;
        try {
            status = LocalModelInfo.RuntimeStatus.valueOf(entity.runtimeStatus);
        } catch (IllegalArgumentException e) {
            status = LocalModelInfo.RuntimeStatus.IMPORTED_NO_NATIVE_RUNTIME;
        }
        return new LocalModelInfo(
                entity.id,
                entity.displayName,
                entity.format,
                entity.uriString,
                entity.fileSizeBytes,
                entity.estimatedRamMb,
                status,
                entity.importedAt
        );
    }

    public static LocalModelEntity toEntity(LocalModelInfo info) {
        return new LocalModelEntity(
                info.getId(),
                info.getDisplayName(),
                info.getFormat(),
                info.getUriString(),
                info.getFileSizeBytes(),
                info.getEstimatedRamMb(),
                info.getRuntimeStatus().name(),
                info.getImportedAt()
        );
    }

    public static List<LocalModelInfo> toLocalModelList(List<LocalModelEntity> entities) {
        List<LocalModelInfo> result = new ArrayList<>();
        if (entities == null) return result;
        for (LocalModelEntity e : entities) {
            result.add(toDomain(e));
        }
        return result;
    }
}
