package com.chatai.an.ai.context;

import com.chatai.an.ai.tool.ToolRegistry;
import com.chatai.an.domain.model.AgentMode;
import com.chatai.an.domain.model.AiModel;
import com.chatai.an.domain.model.AiRequest;
import com.chatai.an.domain.model.CapabilityStatus;
import com.chatai.an.domain.model.Conversation;
import com.chatai.an.domain.model.MemoryItem;
import com.chatai.an.domain.model.MemoryScope;
import com.chatai.an.domain.model.Message;
import com.chatai.an.domain.model.ModelCapability;
import com.chatai.an.domain.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Builds the ephemeral prompt context sent to the AI model:
 * - System instructions + Agent mode (Chat Agent vs Coding Agent) + active workspace
 * - Relevant active AI Memory items (never full raw DB dump)
 * - Optional compacted conversation summary (strictly separate from long-term AI Memory)
 * - Enabled tool catalog + cross-provider invocation contract when tools are permitted
 * - Token-budget-trimmed message history for the target conversation only.
 */
public class ContextManager {

    private static final String BASE_CHAT_AGENT_PROMPT =
            "Your official name and personal identity is AnAI (Chat Mode - AndroidHarness Architecture). You are a helpful, accurate, and fast conversational assistant running on Android. "
                    + "Whenever the user asks for your name, who you are, or your identity, you must always state that your name is AnAI (adapt your tone contextually, e.g., friendly, polite, or playful if asked cutely). "
                    + "Never claim model names (such as GPT, Gemini, Claude, Llama) or provider names (such as OpenAI, Anthropic, Google) as your personal identity—keep your identity as AnAI completely separate from any technical underlying model or provider specs. "
                    + "Response Presentation Rules: Answer directly and stay strictly on-topic to what the user asked. Avoid bloated introductions, filler transitions, unsolicited scope expansion, or repetitive summaries unless asked. "
                    + "Answer using the user's language when clear, and use Markdown cleanly for tables, blockquotes, links, and code blocks. "
                    + "Translation Rules (STRICT): Whenever the user asks to translate text (e.g., translate, dịch, sang tiếng..., nghĩa là gì): "
                    + "(1) Return ONLY the direct, natural, context-appropriate translation. "
                    + "(2) Do NOT include lead-in sentences, intros, or closing remarks. "
                    + "(3) Provide ONE default translation only—do NOT add alternative options, commentary, or explanations unless the user explicitly asks. "
                    + "(4) Do NOT add advice, follow-up questions, suggestions, or emojis outside the original text. "
                    + "(5) Preserve the original meaning, tone, and formatting (line breaks, lists, placeholders, Markdown, code). "
                    + "In Chat mode, keep interactions conversational, research-focused, and read-only: you may inspect workspace files (`fs_read_file`, `fs_list_dir`, `fs_search_files`, `fs_grep`), search or fetch web pages, recall/remember user facts, read attachments, and query read-only device/ADB status (`device_info`, `adb_device_manager`, `adb_package_manager` inspection). "
                    + "If the user asks you to edit workspace files, install packages, run builds/scripts, or execute modifying shell/Shizuku commands, explain briefly what you plan to change and advise switching to **Code** mode (or tapping Continue in Code / `/code`).";

    private static final String BASE_CODING_AGENT_PROMPT =
            "Your official name and personal identity is AnAI (Code Agent Mode - AndroidHarness Architecture). "
                    + "You are an autonomous, expert software engineering and device-automation agent capable of designing, writing, editing, building, and testing code as well as running terminal/Termux/Shizuku tools. "
                    + "Whenever the user asks for your name or identity, state your name is AnAI. "
                    + "Keep explanations direct and concise—report concrete changes, real command outputs, and verified test results without filler or unsolicited scope expansion. If asked to translate text or code strings, return ONLY one direct natural translation matching the source format with no extra commentary or emojis. "
                    + "Follow the rigorous 4-step engineering loop:\n"
                    + "1. EXPLORE: Inspect directory layouts (`fs_list_dir`), search symbols (`fs_grep`, `fs_search_files`), and read target files (`fs_read_file`).\n"
                    + "2. PLAN: Formulate a precise engineering plan to implement features or fix bugs.\n"
                    + "3. EXECUTE: Apply surgical modifications (`fs_edit_file`, `fs_write_file`, `fs_mkdir`) and execute builds, tests, or scripts using the In-App Terminal (`terminal_run`, `terminal_script`), Termux (`termux_run`), or Shizuku (`adb_shell`, `adb_package_manager`, `adb_system_settings`, `adb_activity_manager`, `adb_device_manager`, `adb_logcat`). Notice that privileged or mutating actions will pause cleanly for inline user confirmation.\n"
                    + "4. VERIFY & SELF-CORRECT: If a build error or test failure occurs, analyze stderr/tool output, fix the issue in code, and re-verify until success.\n"
                    + "Never give up or fabricate success. Always report real command outputs and test results.";

    private static final String MEMORY_TOOLS_PROMPT =
            "\n\nMemory Tools Available:\n"
                    + "When the user shares durable personal preferences, profile facts, project details, or decisions—or asks you to remember/forget/recall something—you may emit a tool call tag before your response:\n"
                    + "<chatai_tool name=\"remember\">{\"operation\":\"save\",\"category\":\"PREFERENCE\",\"scope\":\"GLOBAL\",\"area\":\"CHAT\",\"importance\":4,\"content\":\"concise fact\"}</chatai_tool>\n"
                    + "<chatai_tool name=\"recall\">{\"query\":\"search keywords\"}</chatai_tool>\n"
                    + "Only call remember for meaningful durable information (never save greetings or filler).";

    public AiRequest buildRequest(
            String conversationId,
            AiModel model,
            List<Message> fullConversationMessages,
            List<MemoryItem> activeMemories,
            boolean deepThinkingEnabled,
            String customSystemPrompt
    ) {
        return buildRequest(null, conversationId, model, fullConversationMessages, activeMemories, false, deepThinkingEnabled, customSystemPrompt);
    }

    public AiRequest buildRequest(
            Conversation conversation,
            String conversationId,
            AiModel model,
            List<Message> fullConversationMessages,
            List<MemoryItem> activeMemories,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled,
            String customSystemPrompt
    ) {
        return buildRequest(
                conversation,
                conversationId,
                model,
                fullConversationMessages,
                activeMemories,
                includeMemoryToolInstructions,
                deepThinkingEnabled,
                customSystemPrompt,
                AgentMode.CHAT,
                "",
                Collections.emptyList()
        );
    }

    public AiRequest buildRequest(
            Conversation conversation,
            String conversationId,
            AiModel model,
            List<Message> fullConversationMessages,
            List<MemoryItem> activeMemories,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled,
            String customSystemPrompt,
            AgentMode agentMode,
            String activeWorkspacePath,
            List<ToolDefinition> enabledTools
    ) {
        boolean nativeToolsUnsupported = model != null
                && model.getCapabilityStatus(ModelCapability.TOOL_CALLING) == CapabilityStatus.UNSUPPORTED;
        List<ToolDefinition> effectiveTools = enabledTools != null
                ? enabledTools
                : Collections.emptyList();

        String systemPrompt = assembleSystemPrompt(
                customSystemPrompt,
                activeMemories,
                conversation,
                includeMemoryToolInstructions,
                deepThinkingEnabled,
                agentMode,
                activeWorkspacePath,
                effectiveTools,
                nativeToolsUnsupported
        );

        int maxWindow = model != null ? model.getContextWindowTokens() : 32000;
        int inputBudgetTokens = Math.max(2048, (int) (maxWindow * 0.65));
        int systemTokens = estimateTokens(systemPrompt);
        int remainingBudget = Math.max(1024, inputBudgetTokens - systemTokens);

        long compactedUntil = conversation != null ? conversation.getCompactedUntil() : 0L;
        List<Message> trimmed = trimMessagesToBudget(conversationId, fullConversationMessages, remainingBudget, compactedUntil);

        return new AiRequest(
                UUID.randomUUID().toString(),
                conversationId,
                model,
                systemPrompt,
                trimmed,
                deepThinkingEnabled,
                model == null || model.isStreamingSupported(),
                effectiveTools
        );
    }

    public String assembleSystemPrompt(String customSystemPrompt, List<MemoryItem> activeMemories, boolean deepThinkingEnabled) {
        return assembleSystemPrompt(customSystemPrompt, activeMemories, null, false, deepThinkingEnabled);
    }

    public String assembleSystemPrompt(
            String customSystemPrompt,
            List<MemoryItem> activeMemories,
            Conversation conversation,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled
    ) {
        return assembleSystemPrompt(
                customSystemPrompt,
                activeMemories,
                conversation,
                includeMemoryToolInstructions,
                deepThinkingEnabled,
                AgentMode.CHAT,
                "",
                Collections.emptyList(),
                false
        );
    }

    public String assembleSystemPrompt(
            String customSystemPrompt,
            List<MemoryItem> activeMemories,
            Conversation conversation,
            boolean includeMemoryToolInstructions,
            boolean deepThinkingEnabled,
            AgentMode agentMode,
            String activeWorkspacePath,
            List<ToolDefinition> enabledTools,
            boolean toolsUnsupportedByModel
    ) {
        StringBuilder sb = new StringBuilder();
        if (customSystemPrompt != null && !customSystemPrompt.trim().isEmpty()) {
            sb.append(customSystemPrompt.trim());
        } else {
            sb.append(agentMode == AgentMode.CODING ? BASE_CODING_AGENT_PROMPT : BASE_CHAT_AGENT_PROMPT);
        }

        if (deepThinkingEnabled) {
            sb.append("\n\nThink carefully step by step before giving your final answer.");
        }

        if (conversation != null && !conversation.getProjectId().isEmpty()) {
            sb.append("\n\nActive Project Scope: ").append(conversation.getProjectId());
        }

        if (activeWorkspacePath != null && !activeWorkspacePath.trim().isEmpty()) {
            sb.append("\nActive Workspace Directory: ").append(activeWorkspacePath.trim());
        }

        if (activeMemories != null && !activeMemories.isEmpty()) {
            StringBuilder memBlock = new StringBuilder();
            for (MemoryItem item : activeMemories) {
                if (item != null && item.isEnabled() && !item.isArchived() && !item.getContent().isEmpty()) {
                    memBlock.append("- [")
                            .append(item.getCategory().name())
                            .append("/")
                            .append(item.getScope() == MemoryScope.PROJECT ? "PROJECT:" + item.getProjectId() : "GLOBAL")
                            .append("] ")
                            .append(item.getContent())
                            .append("\n");
                }
            }
            if (memBlock.length() > 0) {
                sb.append("\n\nRelevant Long-Term Memories (use when helpful):\n").append(memBlock);
            }
        }

        if (conversation != null && !conversation.getCompactionSummary().isEmpty()) {
            sb.append("\n\nEarlier Conversation Summary (compacted history, not long-term memory):\n")
                    .append(conversation.getCompactionSummary());
        }

        if (enabledTools != null && !enabledTools.isEmpty()) {
            if (toolsUnsupportedByModel) {
                sb.append("\n\nEnabled Tools (Native schema calling is disabled for this model; invoke tools by emitting `<chatai_tool name=\"tool_name\">{\"param\":\"value\"}</chatai_tool>` tags directly and the app runtime will execute them and return real observations):\n");
            } else {
                sb.append("\n\nEnabled Tools (Call via native tool calls OR `<chatai_tool name=\"tool_name\">{\"param\":\"value\"}</chatai_tool>` tags before answering; the app executes them and feeds real observations back to you):\n");
            }
            sb.append(ToolRegistry.formatCatalogSummary(enabledTools));
            if (agentMode == AgentMode.CODING) {
                sb.append("\nExamples:\n")
                        .append("<chatai_tool name=\"fs_list_dir\">{\"path\":\".\"}</chatai_tool>\n")
                        .append("<chatai_tool name=\"terminal_run\">{\"command\":\"ls -la\"}</chatai_tool>");
            } else {
                sb.append("\nExample: <chatai_tool name=\"fs_list_dir\">{\"path\":\".\"}</chatai_tool>");
            }
            sb.append("\nOnly invoke tools when genuinely needed. Wait for real tool output before stating results.");
        } else if (includeMemoryToolInstructions) {
            sb.append(MEMORY_TOOLS_PROMPT);
        }

        sb.append("\n\nOptional Follow-Up Chips: ONLY after complex multi-step exploration or coding tasks where clear next steps genuinely help, you may optionally append at most 2 brief next-step prompts as:\n")
                .append("<chatai_followups>[\"Short next prompt 1\",\"Short next prompt 2\"]</chatai_followups>\n")
                .append("NEVER emit <chatai_followups> for translations, short factual answers, or direct single-turn requests.");

        return sb.toString();
    }

    public List<Message> trimMessagesToBudget(String conversationId, List<Message> allMessages, int tokenBudget) {
        return trimMessagesToBudget(conversationId, allMessages, tokenBudget, 0L);
    }

    public List<Message> trimMessagesToBudget(String conversationId, List<Message> allMessages, int tokenBudget, long compactedUntil) {
        if (allMessages == null || allMessages.isEmpty()) {
            return Collections.emptyList();
        }
        List<Message> selectedReversed = new ArrayList<>();
        int usedTokens = 0;

        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Message msg = allMessages.get(i);
            if (msg == null) continue;
            if (conversationId != null && !conversationId.isEmpty()
                    && msg.getConversationId() != null && !msg.getConversationId().isEmpty()
                    && !conversationId.equals(msg.getConversationId())) {
                continue;
            }
            if (msg.isError()) continue;
            if (compactedUntil > 0L && msg.getCreatedAt() <= compactedUntil && !selectedReversed.isEmpty()) {
                continue;
            }

            int msgTokens = estimateTokens(msg.getContent()) + 16;
            if (!selectedReversed.isEmpty() && usedTokens + msgTokens > tokenBudget) {
                break;
            }
            selectedReversed.add(msg);
            usedTokens += msgTokens;
        }
        Collections.reverse(selectedReversed);
        return selectedReversed;
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.length() + 3) / 4);
    }
}
