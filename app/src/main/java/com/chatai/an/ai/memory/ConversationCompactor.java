package com.chatai.an.ai.memory;

import com.chatai.an.ai.context.ContextManager;
import com.chatai.an.ai.core.AiCore;
import com.chatai.an.ai.core.ExecutionHandle;
import com.chatai.an.ai.provider.AiStreamListener;
import com.chatai.an.domain.model.AiError;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.AiResponse;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.ExecutionState;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.repository.ConversationRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Compacts long conversation histories by generating a structured history summary stored
 * on Conversation (compactionSummary + compactedUntil), while NEVER deleting original
 * messages in Room and keeping conversation summaries separate from long-term AI Memories.
 */
public class ConversationCompactor {

    private static final int KEEP_RECENT_MESSAGES = 4;

    private final AiCore aiCore;
    private final ConversationRepository conversationRepository;

    public ConversationCompactor(AiCore aiCore, ConversationRepository conversationRepository) {
        this.aiCore = aiCore;
        this.conversationRepository = conversationRepository;
    }

    public boolean needsCompaction(AiModel model, List<Message> allMessages, long compactedUntil) {
        if (model == null || allMessages == null || allMessages.size() <= KEEP_RECENT_MESSAGES + 2) {
            return false;
        }
        int window = model.getContextWindowTokens();
        int triggerBudget = Math.max(2400, (int) (window * 0.65));
        int uncompactedTokens = 0;
        int uncompactedCount = 0;
        for (Message m : allMessages) {
            if (m == null || m.isError() || m.getCreatedAt() <= compactedUntil) continue;
            uncompactedTokens += ContextManager.estimateTokens(m.getContent()) + 16;
            uncompactedCount++;
        }
        return uncompactedCount > KEEP_RECENT_MESSAGES + 2 && uncompactedTokens > triggerBudget;
    }

    public Conversation compactIfNeededSync(Conversation conversation, AiModel model, List<Message> allMessages) {
        if (conversation == null || model == null || allMessages == null) {
            return conversation;
        }
        long existingCutoff = conversation.getCompactedUntil();
        if (!needsCompaction(model, allMessages, existingCutoff)) {
            return conversation;
        }

        List<Message> uncompacted = new ArrayList<>();
        for (Message m : allMessages) {
            if (m != null && !m.isError() && m.getCreatedAt() > existingCutoff) {
                uncompacted.add(m);
            }
        }
        if (uncompacted.size() <= KEEP_RECENT_MESSAGES) {
            return conversation;
        }

        int sliceEnd = uncompacted.size() - KEEP_RECENT_MESSAGES;
        List<Message> sliceToCompact = uncompacted.subList(0, sliceEnd);
        long newCutoff = sliceToCompact.get(sliceToCompact.size() - 1).getCreatedAt();

        StringBuilder transcript = new StringBuilder();
        if (!conversation.getCompactionSummary().isEmpty()) {
            transcript.append("Previous conversation summary:\n")
                    .append(conversation.getCompactionSummary())
                    .append("\n\n");
        }
        transcript.append("Additional messages to fold into the conversation summary:\n");
        for (Message m : sliceToCompact) {
            String role = m.getRole() == MessageRole.USER ? "User" : "Assistant";
            String content = m.getContent();
            if (content.length() > 1200) {
                content = content.substring(0, 1200) + "…";
            }
            transcript.append(role).append(": ").append(content).append("\n\n");
        }

        Message promptMsg = new Message(
                UUID.randomUUID().toString(),
                conversation.getId(),
                MessageRole.USER,
                transcript.toString(),
                model.getId(),
                false,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                Collections.emptyList()
        );

        AiRequest compactionReq = new AiRequest(
                UUID.randomUUID().toString(),
                conversation.getId(),
                model,
                "Summarize the conversation state below into concise bullet points capturing goals, key facts, code files/symbols, decisions, and open questions. Do not add commentary.",
                Collections.singletonList(promptMsg),
                false,
                false
        );

        AtomicReference<String> summaryRef = new AtomicReference<>();
        aiCore.executeBlocking(compactionReq, new ExecutionHandle(), new AiStreamListener() {
            @Override
            public void onStateChanged(ExecutionState state) {
            }

            @Override
            public void onThinkingDelta(String delta) {
            }

            @Override
            public void onTextDelta(String delta) {
            }

            @Override
            public void onCompleted(AiResponse response) {
                if (response != null && response.getContent() != null && !response.getContent().trim().isEmpty()) {
                    summaryRef.set(response.getContent().trim());
                }
            }

            @Override
            public void onError(AiError error) {
            }
        }, null);

        String summary = summaryRef.get();
        if (summary != null && !summary.isEmpty()) {
            conversationRepository.updateConversationCompaction(conversation.getId(), summary, newCutoff);
            return new Conversation(
                    conversation.getId(),
                    conversation.getTitle(),
                    conversation.getModelId(),
                    conversation.getProjectId(),
                    summary,
                    newCutoff,
                    conversation.getCreatedAt(),
                    System.currentTimeMillis(),
                    conversation.getLastMessagePreview()
            );
        }
        return conversation;
    }
}
