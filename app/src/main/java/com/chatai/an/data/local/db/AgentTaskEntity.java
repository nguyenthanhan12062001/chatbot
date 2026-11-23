package com.chatai.an.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "agent_tasks")
public class AgentTaskEntity {
    @PrimaryKey
    @NonNull
    public String id;

    @NonNull
    public String goal;

    @NonNull
    @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @NonNull
    @ColumnInfo(name = "model_id")
    public String modelId;

    @NonNull
    public String status;

    @NonNull
    @ColumnInfo(name = "steps_json")
    public String stepsJson;

    @NonNull
    @ColumnInfo(name = "final_result")
    public String finalResult;

    @NonNull
    @ColumnInfo(name = "error_message")
    public String errorMessage;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    public AgentTaskEntity(
            @NonNull String id,
            @NonNull String goal,
            @NonNull String conversationId,
            @NonNull String modelId,
            @NonNull String status,
            @NonNull String stepsJson,
            @NonNull String finalResult,
            @NonNull String errorMessage,
            long createdAt,
            long updatedAt
    ) {
        this.id = id;
        this.goal = goal;
        this.conversationId = conversationId;
        this.modelId = modelId;
        this.status = status;
        this.stepsJson = stepsJson;
        this.finalResult = finalResult;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
