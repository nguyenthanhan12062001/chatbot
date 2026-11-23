package com.chatai.an.ai.provider;

import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.domain.model.AccessType;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.ProtocolType;

import java.util.List;

public interface AiProvider {
    String getProviderId();

    AccessType getAccessType();

    ProtocolType getProtocolType();

    /**
     * Executes the request synchronously on a background thread and emits streaming/completion events to listener.
     */
    void execute(AiRequest request, String apiKey, ExecutionHandle handle, AiStreamListener listener);

    /**
     * Fetches available models from remote endpoint.
     */
    List<AiModel> fetchModels(String apiKey) throws Exception;

    /**
     * Tests connectivity and authentication against the configured endpoint.
     */
    String testConnection(String apiKey) throws Exception;
}
