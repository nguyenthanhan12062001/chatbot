package com.chatai.an.ai.provider;

import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.SourceItem;
import com.chatai.an.domain.model.TimelineEntry;

import java.util.List;

public interface AiStreamListener {
    void onStateChanged(ExecutionState state);

    void onThinkingDelta(String delta);

    void onTextDelta(String delta);

    default void onCommentaryDelta(String commentary) {
    }

    default void onTimelineUpdated(List<TimelineEntry> timeline, List<SourceItem> sources) {
    }

    void onCompleted(AiResponse response);

    void onError(AiError error);
}
