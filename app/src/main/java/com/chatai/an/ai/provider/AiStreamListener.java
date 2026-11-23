package com.chatai.an.ai.provider;

import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.ExecutionState;

public interface AiStreamListener {
    void onStateChanged(ExecutionState state);

    void onThinkingDelta(String delta);

    void onTextDelta(String delta);

    void onCompleted(AiResponse response);

    void onError(AiError error);
}
