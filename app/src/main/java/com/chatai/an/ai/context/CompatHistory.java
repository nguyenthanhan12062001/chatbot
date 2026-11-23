package com.chatai.an.ai.context;

import com.chatai.an.ai.core.AiCore;
import com.chatai.an.domain.model.Attachment;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.MessageRole;
import com.chatai.an.domain.model.ProtocolType;
import com.chatai.an.domain.model.ToolCallRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cross-provider conversation history normalizer (Java counterpart of AndroidHarness CompatHistory.kt).
 *
 * Ensures switching models or providers mid-session (e.g., Gemini -> Claude -> OpenAI -> Pollinations)
 * keeps the exact same session ID and full history while adapting role alternation, leading turns,
 * and historical tool executions into a provider-compatible format without orphaned tool call IDs.
 */
public final class CompatHistory {

    private CompatHistory() {
    }

    public static List<Message> normalizeForProvider(List<Message> rawMessages, ProtocolType targetProtocol) {
        return normalizeForProvider(rawMessages, targetProtocol, false);
    }

    public static List<Message> normalizeForProvider(
            List<Message> rawMessages,
            ProtocolType targetProtocol,
            boolean isIntraRunLoop
    ) {
        if (rawMessages == null || rawMessages.isEmpty()) {
            return Collections.emptyList();
        }

        List<Message> cleaned = new ArrayList<>();
        for (Message msg : rawMessages) {
            if (msg == null || msg.isError()) continue;

            MessageRole normalizedRole = msg.getRole() == MessageRole.ASSISTANT
                    ? MessageRole.ASSISTANT
                    : MessageRole.USER;

            String content = msg.getContent() != null ? msg.getContent() : "";
            if (!isIntraRunLoop) {
                content = AiCore.stripControlTags(content);
            }
            content = content.trim();

            if (normalizedRole == MessageRole.ASSISTANT && !isIntraRunLoop) {
                String toolSummary = formatToolHistorySummary(msg.getToolRecords());
                if (!toolSummary.isEmpty()) {
                    content = content.isEmpty()
                            ? toolSummary
                            : (content + "\n\n" + toolSummary);
                }
            }

            List<Attachment> attachments = msg.getAttachments() != null
                    ? msg.getAttachments()
                    : Collections.emptyList();
            if (content.isEmpty() && attachments.isEmpty()) {
                continue;
            }

            if (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).getRole() == normalizedRole) {
                Message prev = cleaned.remove(cleaned.size() - 1);
                cleaned.add(mergeSameRole(prev, msg, normalizedRole, content, attachments));
            } else {
                cleaned.add(new Message(
                        msg.getId(),
                        msg.getConversationId(),
                        normalizedRole,
                        content,
                        "",
                        msg.getModelId(),
                        msg.getProviderId(),
                        msg.getAccessType(),
                        msg.isDeepThinkingEnabled(),
                        msg.getTokenUsage(),
                        false,
                        msg.getCreatedAt(),
                        msg.getUpdatedAt(),
                        attachments,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList()
                ));
            }
        }

        if (cleaned.isEmpty()) {
            return Collections.emptyList();
        }

        if ((targetProtocol == ProtocolType.ANTHROPIC_MESSAGES || targetProtocol == ProtocolType.GEMINI_API)
                && cleaned.get(0).getRole() == MessageRole.ASSISTANT) {
            Message first = cleaned.get(0);
            cleaned.add(0, new Message(
                    "compat-prefix-" + first.getCreatedAt(),
                    first.getConversationId(),
                    MessageRole.USER,
                    "[Continued conversation context]",
                    first.getModelId(),
                    false,
                    Math.max(0L, first.getCreatedAt() - 1L),
                    Math.max(0L, first.getCreatedAt() - 1L),
                    Collections.emptyList()
            ));
        }

        return Collections.unmodifiableList(cleaned);
    }

    private static Message mergeSameRole(
            Message first,
            Message second,
            MessageRole role,
            String secondContent,
            List<Attachment> secondAttachments
    ) {
        String mergedText;
        if (first.getContent().isEmpty()) {
            mergedText = secondContent;
        } else if (secondContent.isEmpty()) {
            mergedText = first.getContent();
        } else {
            mergedText = first.getContent() + "\n\n" + secondContent;
        }

        List<Attachment> mergedAttachments = new ArrayList<>();
        if (first.getAttachments() != null) mergedAttachments.addAll(first.getAttachments());
        if (secondAttachments != null) mergedAttachments.addAll(secondAttachments);

        return new Message(
                second.getId(),
                second.getConversationId(),
                role,
                mergedText,
                "",
                second.getModelId(),
                second.getProviderId(),
                second.getAccessType(),
                second.isDeepThinkingEnabled(),
                second.getTokenUsage(),
                false,
                first.getCreatedAt(),
                second.getUpdatedAt(),
                mergedAttachments,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    public static String formatToolHistorySummary(List<ToolCallRecord> records) {
        if (records == null || records.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("[Prior tool actions in this turn:");
        int count = 0;
        for (ToolCallRecord rec : records) {
            if (rec == null || rec.getToolName().isEmpty()) continue;
            count++;
            sb.append("\n- ").append(rec.getToolName())
                    .append(" (").append(rec.getStatus().name()).append(")");
            if (!rec.getArgsPreview().isEmpty()) {
                sb.append(" args=").append(compactInline(rec.getArgsPreview(), 140));
            }
            String out = !rec.getOutputPreview().isEmpty() ? rec.getOutputPreview() : rec.getErrorMessage();
            if (!out.isEmpty()) {
                sb.append(" -> ").append(compactInline(out, 220));
            }
        }
        if (count == 0) return "";
        sb.append("]");
        return sb.toString();
    }

    private static String compactInline(String value, int maxLen) {
        if (value == null) return "";
        String single = value.replaceAll("\\s+", " ").trim();
        if (single.length() <= maxLen) return single;
        return single.substring(0, maxLen) + "...";
    }
}
